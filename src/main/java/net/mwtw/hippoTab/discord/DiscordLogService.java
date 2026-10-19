package net.mwtw.hippoTab.discord;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * One-way log of this server's chat, private messages and nick changes to Discord
 * webhooks (settings under {@code discord} in config.yml, read live). Each server points
 * at its own channel; nothing is read back from Discord. Players are always named by
 * their real login name, with the nick alongside when they have one.
 */
public final class DiscordLogService implements Listener {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Plugin plugin;
    private final DiscordWebhook webhook;
    private final Function<Player, String> realNameResolver;
    // Returns null for a player without a nick. Asked directly rather than inferred from
    // player.getName(), which only differs from the real name while the profile itself
    // is renamed (not the case with nick.packet-name.enabled: false, for one).
    private final Function<Player, String> nicknameResolver;

    public DiscordLogService(Plugin plugin, DiscordWebhook webhook, Function<Player, String> realNameResolver,
                             Function<Player, String> nicknameResolver) {
        this.plugin = plugin;
        this.webhook = webhook;
        this.realNameResolver = realNameResolver;
        this.nicknameResolver = nicknameResolver;
    }

    /** The name of this server ("分流") shown in reports. */
    public String serverName() {
        String name = plugin.getConfig().getString("server-name", "");
        if (name == null || name.isBlank()) {
            name = plugin.getConfig().getString("chat-filter.server-name", "");
        }
        return name == null ? "" : name;
    }

    /** "RealName" or "RealName (Nick)" — already escaped for Discord. */
    public String displayName(Player player) {
        return DiscordWebhook.escapeMarkdown(plainName(player));
    }

    /** "RealName" or "RealName (Nick)" as plain text (for embeds and the server log). */
    public String plainName(Player player) {
        String real = realNameResolver.apply(player);
        String nick = nicknameResolver.apply(player);
        return nick == null || nick.isBlank() || nick.equals(real) ? real : real + " (" + nick + ")";
    }

    // MONITOR: log what was really sent, after every other plugin had its say. The text
    // is the message as typed (before the word filter), which is what staff need to see.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfig().getBoolean("discord.chat-log.enabled", false)) {
            return;
        }
        String url = plugin.getConfig().getString("discord.chat-log.webhook-url", "");
        String format = plugin.getConfig().getString("discord.chat-log.format", "`{time}` **{name}**: {message}");
        String message = PlainTextComponentSerializer.plainText().serialize(event.originalMessage());
        webhook.line(url, base(format)
                .replace("{name}", displayName(event.getPlayer()))
                .replace("{message}", DiscordWebhook.escapeMarkdown(message)));
    }

    /** Names must already be escaped (see {@link #displayName}); the message is escaped here. */
    public void privateMessage(String senderName, String targetName, String message) {
        if (!plugin.getConfig().getBoolean("discord.private-log.enabled", false)) {
            return;
        }
        String url = plugin.getConfig().getString("discord.private-log.webhook-url", "");
        if (url == null || url.isBlank()) {
            url = plugin.getConfig().getString("discord.chat-log.webhook-url", "");
        }
        String format = plugin.getConfig().getString("discord.private-log.format",
                "`{time}` ✉ **{sender}** → **{target}**: {message}");
        webhook.line(url, base(format)
                .replace("{sender}", senderName)
                .replace("{target}", targetName)
                .replace("{message}", DiscordWebhook.escapeMarkdown(message)));
    }

    /** A nick was set, changed or cleared: posted as an embed, one field per detail. */
    public void nickChange(String action, String realName, String nickname, String rank, String skin, String actor) {
        if (!plugin.getConfig().getBoolean("discord.nick-log.enabled", false)) {
            return;
        }
        String url = plugin.getConfig().getString("discord.nick-log.webhook-url", "");
        if (url == null || url.isBlank()) {
            url = plugin.getConfig().getString("discord.chat-log.webhook-url", "");
        }
        String title;
        int color;
        switch (action) {
            case "SET" -> {
                title = "🏷️ 設定暱稱";
                color = 0x57F287;
            }
            case "RANK" -> {
                title = "🏷️ 變更顯示階級";
                color = 0xFEE75C;
            }
            default -> {
                title = "🏷️ 清除暱稱";
                color = 0xED4245;
            }
        }
        List<String> fields = new ArrayList<>();
        addField(fields, "玩家", realName, true);
        addField(fields, "暱稱", nickname, true);
        addField(fields, "顯示階級", rank, true);
        addField(fields, "皮膚", skin, false);
        addField(fields, "操作者", actor, true);
        addField(fields, "分流", serverName(), true);
        webhook.payload(url, "{\"embeds\":[{"
                + "\"title\":\"" + DiscordWebhook.escapeJson(title) + "\","
                + "\"color\":" + color + ","
                + "\"fields\":[" + String.join(",", fields) + "],"
                + "\"timestamp\":\"" + Instant.now() + "\""
                + "}],\"allowed_mentions\":{\"parse\":[]}}");
    }

    /** Adds one embed field; a detail that does not apply (blank) is left out. */
    private static void addField(List<String> fields, String name, String value, boolean inline) {
        if (value == null || value.isBlank()) {
            return;
        }
        // Discord caps a field value at 1024 characters (a skin URL can be long).
        String shown = value.length() > 1000 ? value.substring(0, 1000) + "…" : value;
        fields.add("{\"name\":\"" + DiscordWebhook.escapeJson(name)
                + "\",\"value\":\"" + DiscordWebhook.escapeJson(DiscordWebhook.escapeMarkdown(shown))
                + "\",\"inline\":" + inline + "}");
    }

    /** This server (strictly: the plugin) just started, or is shutting down. */
    public void serverStatus(boolean started) {
        if (!plugin.getConfig().getBoolean("discord.server-log.enabled", false)) {
            return;
        }
        String url = plugin.getConfig().getString("discord.server-log.webhook-url", "");
        if (url == null || url.isBlank()) {
            url = plugin.getConfig().getString("discord.chat-log.webhook-url", "");
        }
        String format = started
                ? plugin.getConfig().getString("discord.server-log.start-format", "`{time}` 🟢 **{server}** 伺服器已開啟")
                : plugin.getConfig().getString("discord.server-log.stop-format", "`{time}` 🔴 **{server}** 伺服器已關閉");
        webhook.line(url, base(format));
    }

    private String base(String format) {
        return format
                .replace("{time}", LocalTime.now().format(TIME))
                .replace("{server}", DiscordWebhook.escapeMarkdown(serverName()));
    }
}
