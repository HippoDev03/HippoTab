package net.mwtw.hippoTab.pm;

import net.mwtw.hippoTab.discord.DiscordLogService;
import net.mwtw.hippoTab.discord.DiscordWebhook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Watches the private-message commands players run on this server (whichever plugin
 * implements them — the command names come from config) and hands each message to the
 * staff spy and the Discord log. It only observes; it never changes or blocks a command.
 */
public final class PrivateMessageListener implements Listener {
    private final Plugin plugin;
    private final SpyService spyService;
    private final DiscordLogService discordLog;
    private final Function<Player, String> realNameResolver;
    // Who each player last exchanged a private message with, to name the target of /r.
    private final Map<UUID, UUID> lastPartner = new ConcurrentHashMap<>();

    public PrivateMessageListener(Plugin plugin, SpyService spyService, DiscordLogService discordLog,
                                  Function<Player, String> realNameResolver) {
        this.plugin = plugin;
        this.spyService = spyService;
        this.discordLog = discordLog;
        this.realNameResolver = realNameResolver;
    }

    // MONITOR: after the nick bridge (LOWEST) turned nicknames into real names, and only
    // for commands no other plugin cancelled.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw == null || raw.length() < 2 || raw.charAt(0) != '/') {
            return;
        }
        String[] parts = raw.substring(1).trim().split("\\s+", 3);
        String label = parts[0].toLowerCase(Locale.ROOT);
        int namespace = label.indexOf(':');
        if (namespace >= 0) {
            label = label.substring(namespace + 1);
        }

        Player sender = event.getPlayer();
        Player target;
        String targetName;
        String message;
        if (contains("private-messages.commands", label)) {
            if (parts.length < 3) {
                return;
            }
            target = Bukkit.getPlayerExact(parts[1]);
            targetName = target != null ? realNameResolver.apply(target) : parts[1];
            message = parts[2];
        } else if (contains("private-messages.reply-commands", label)) {
            if (parts.length < 2) {
                return;
            }
            UUID partner = lastPartner.get(sender.getUniqueId());
            target = partner == null ? null : Bukkit.getPlayer(partner);
            targetName = target != null ? realNameResolver.apply(target) : "?";
            message = parts.length == 3 ? parts[1] + " " + parts[2] : parts[1];
        } else {
            return;
        }

        if (target != null) {
            lastPartner.put(sender.getUniqueId(), target.getUniqueId());
            lastPartner.put(target.getUniqueId(), sender.getUniqueId());
        }

        spyService.relay(sender.getUniqueId(), target == null ? null : target.getUniqueId(),
                realNameResolver.apply(sender), targetName, message, discordLog.serverName());
        discordLog.privateMessage(discordLog.displayName(sender),
                target != null ? discordLog.displayName(target) : DiscordWebhook.escapeMarkdown(targetName),
                message);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastPartner.remove(event.getPlayer().getUniqueId());
    }

    private boolean contains(String path, String label) {
        List<String> commands = plugin.getConfig().getStringList(path);
        for (String command : commands) {
            if (command != null && command.trim().equalsIgnoreCase(label)) {
                return true;
            }
        }
        return false;
    }
}
