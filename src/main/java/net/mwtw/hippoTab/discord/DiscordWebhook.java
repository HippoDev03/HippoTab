package net.mwtw.hippoTab.discord;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Queued, rate-limit-aware sender for Discord webhooks. Text lines for the same webhook
 * are batched into one Discord message, so a busy chat stays far below Discord's limit
 * (about 30 requests a minute per webhook), and a 429 answer pauses that webhook for as
 * long as Discord asks instead of dropping reports.
 */
public final class DiscordWebhook {
    // Discord caps a message at 2000 characters.
    private static final int MAX_CONTENT = 1900;
    private static final int MAX_QUEUE = 1000;
    private static final long PERIOD_TICKS = 40L;

    private record Item(String text, String json) {
    }

    private static final class Channel {
        final ArrayDeque<Item> queue = new ArrayDeque<>();
        long blockedUntil;
        int dropped;
    }

    private final Plugin plugin;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private BukkitTask task;

    public DiscordWebhook(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flush, PERIOD_TICKS, PERIOD_TICKS);
    }

    /** Stops the timer and makes one last attempt to deliver what is still queued. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // Each pass sends one request per webhook; a few passes cover a queued report
        // followed by the "server stopped" line without holding up the shutdown for long.
        for (int pass = 0; pass < 3; pass++) {
            flush();
        }
    }

    /** Queues one line of plain text; lines are joined into as few messages as possible. */
    public void line(String webhookUrl, String text) {
        if (text != null && !text.isBlank()) {
            enqueue(webhookUrl, new Item(text, null));
        }
    }

    /** Queues a complete webhook payload (e.g. an embed), sent as its own message. */
    public void payload(String webhookUrl, String json) {
        enqueue(webhookUrl, new Item(null, json));
    }

    private void enqueue(String webhookUrl, Item item) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        Channel channel = channels.computeIfAbsent(webhookUrl.trim(), ignored -> new Channel());
        synchronized (channel) {
            if (channel.queue.size() >= MAX_QUEUE) {
                channel.queue.pollFirst();
                channel.dropped++;
            }
            channel.queue.addLast(item);
        }
    }

    private void flush() {
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            for (Map.Entry<String, Channel> entry : channels.entrySet()) {
                flushChannel(entry.getKey(), entry.getValue());
            }
        } finally {
            flushing.set(false);
        }
    }

    /** Sends at most one request for this webhook. */
    private void flushChannel(String url, Channel channel) {
        List<Item> batch = new ArrayList<>();
        String body;
        synchronized (channel) {
            if (channel.queue.isEmpty() || System.currentTimeMillis() < channel.blockedUntil) {
                return;
            }
            if (channel.dropped > 0) {
                plugin.getLogger().warning("Discord webhook queue overflowed; dropped " + channel.dropped + " entries.");
                channel.dropped = 0;
            }
            Item head = channel.queue.pollFirst();
            batch.add(head);
            if (head.json() != null) {
                body = head.json();
            } else {
                StringBuilder content = new StringBuilder(truncate(head.text()));
                while (!channel.queue.isEmpty() && channel.queue.peekFirst().json() == null) {
                    String nextLine = truncate(channel.queue.peekFirst().text());
                    if (content.length() + 1 + nextLine.length() > MAX_CONTENT) {
                        break;
                    }
                    content.append('\n').append(nextLine);
                    batch.add(channel.queue.pollFirst());
                }
                // No pings from player text, and no link previews in the log.
                body = "{\"content\":\"" + escapeJson(content.toString())
                        + "\",\"allowed_mentions\":{\"parse\":[]},\"flags\":4}";
            }
        }

        long retryAfterMillis = post(url, body);
        if (retryAfterMillis > 0) {
            synchronized (channel) {
                channel.blockedUntil = System.currentTimeMillis() + retryAfterMillis;
                for (int i = batch.size() - 1; i >= 0; i--) {
                    channel.queue.addFirst(batch.get(i));
                }
            }
        }
    }

    /** Returns 0 when done (delivered or given up), or how long to wait before retrying. */
    private long post(String url, String body) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("User-Agent", "HippoTab/1.0");
            connection.setDoOutput(true);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            if (code == 429) {
                return retryAfterMillis(connection.getHeaderField("Retry-After"));
            }
            if (code < 200 || code >= 300) {
                plugin.getLogger().warning("Discord webhook returned HTTP " + code);
            }
            return 0;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send Discord webhook: " + e.getMessage());
            return 0;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static long retryAfterMillis(String header) {
        try {
            return Math.max(1000L, (long) (Double.parseDouble(header.trim()) * 1000.0));
        } catch (RuntimeException e) {
            return 5000L;
        }
    }

    private static String truncate(String text) {
        return text.length() <= MAX_CONTENT ? text : text.substring(0, MAX_CONTENT - 1) + "…";
    }

    /** Escapes Discord markdown so player text is shown exactly as typed. */
    public static String escapeMarkdown(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '*' || c == '_' || c == '~' || c == '`' || c == '|' || c == '>'
                    || c == '#' || c == '[' || c == ']' || c == '(' || c == ')' || c == '-') {
                sb.append('\\');
            }
            sb.append(c == '\n' || c == '\r' ? ' ' : c);
        }
        return sb.toString();
    }

    public static String escapeJson(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
