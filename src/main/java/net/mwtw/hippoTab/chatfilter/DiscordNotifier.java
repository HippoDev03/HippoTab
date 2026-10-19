package net.mwtw.hippoTab.chatfilter;

import net.mwtw.hippoTab.discord.DiscordWebhook;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Builds the chat filter's "address detected" reports and hands them to the webhook queue. */
public final class DiscordNotifier {
    private static final class Throttle {
        long lastSentAt;
        int skipped;
    }

    private final DiscordWebhook webhook;
    private final ConcurrentHashMap<UUID, Throttle> throttles = new ConcurrentHashMap<>();

    public DiscordNotifier(DiscordWebhook webhook) {
        this.webhook = webhook;
    }

    /**
     * Per-player cooldown, so someone flooding flagged messages cannot bury the channel
     * in reports. Returns how many reports were skipped since the last one sent, or -1
     * to skip this one.
     */
    public int acquire(UUID playerId, long cooldownMillis) {
        long now = System.currentTimeMillis();
        int[] result = new int[1];
        throttles.compute(playerId, (id, existing) -> {
            Throttle t = existing != null ? existing : new Throttle();
            if (cooldownMillis > 0 && t.lastSentAt != 0 && now - t.lastSentAt < cooldownMillis) {
                t.skipped++;
                result[0] = -1;
            } else {
                result[0] = t.skipped;
                t.skipped = 0;
                t.lastSentAt = now;
            }
            return t;
        });
        return result[0];
    }

    public void clear(UUID playerId) {
        throttles.remove(playerId);
    }

    public void notify(String webhookUrl, String serverName, String kind, String playerName, String message) {
        List<String> fields = new ArrayList<>();
        if (serverName != null && !serverName.isBlank()) {
            fields.add(field("分流", serverName, true));
        }
        fields.add(field("類型", kind, true));
        fields.add(field("玩家", playerName, false));
        fields.add(field("訊息", message, false));

        webhook.payload(webhookUrl, "{\"embeds\":[{"
                + "\"title\":\"⚠️ 聊天偵測通知\","
                + "\"color\":15548997,"
                + "\"fields\":[" + String.join(",", fields) + "],"
                + "\"timestamp\":\"" + Instant.now() + "\""
                + "}],\"allowed_mentions\":{\"parse\":[]}}");
    }

    /** Builds one Discord embed field object. Discord rejects empty values, so blanks become "-". */
    private static String field(String name, String value, boolean inline) {
        String safe = (value == null || value.isBlank()) ? "-" : value;
        return "{\"name\":\"" + DiscordWebhook.escapeJson(name)
                + "\",\"value\":\"" + DiscordWebhook.escapeJson(safe)
                + "\",\"inline\":" + inline + "}";
    }
}
