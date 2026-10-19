package net.mwtw.hippoTab.api;

import net.mwtw.hippoTab.TabModule;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Entry points for other plugins. Everything here is safe to call from the main thread
 * and does nothing while HippoTab's tab module is not running.
 */
public final class HippoTabApi {

    private static volatile TabModule module;
    private static volatile net.mwtw.hippoTab.pm.SpyService spyService;
    private static volatile net.mwtw.hippoTab.discord.DiscordLogService discordLog;

    private HippoTabApi() {
    }

    /** Called by HippoTab itself on enable/disable. */
    public static void bind(TabModule tabModule) {
        module = tabModule;
    }

    /** Called by HippoTab itself on enable/disable (null to unbind). */
    public static void bindSpy(net.mwtw.hippoTab.pm.SpyService spy,
                               net.mwtw.hippoTab.discord.DiscordLogService discord) {
        spyService = spy;
        discordLog = discord;
    }

    /**
     * Hand a message that only some players can see (e.g. another plugin's party chat) to the
     * private-message spy, so staff with /spy on can read it, and to the Discord private-message
     * log (discord.private-log). Shown with the same format as a private message; {@code target}
     * is whatever label describes the audience.
     *
     * @param senderId the sender, who is not shown their own message as a spy line
     * @param sender   the sender's REAL login name (not a nickname)
     */
    public static void spy(java.util.UUID senderId, String sender, String target, String message) {
        net.mwtw.hippoTab.pm.SpyService current = spyService;
        net.mwtw.hippoTab.discord.DiscordLogService discord = discordLog;
        if (current == null || discord == null || sender == null || target == null || message == null) {
            return;
        }
        current.relay(senderId, null, sender, target, message, discord.serverName());
        Player online = senderId == null ? null : org.bukkit.Bukkit.getPlayer(senderId);
        discord.privateMessage(
                online != null ? discord.displayName(online) : net.mwtw.hippoTab.discord.DiscordWebhook.escapeMarkdown(sender),
                net.mwtw.hippoTab.discord.DiscordWebhook.escapeMarkdown(target), message);
    }

    /**
     * Re-evaluate tab.view-group / tab.member-group for these players and re-apply who can
     * see whom in the tab list right away, instead of waiting for the next periodic refresh.
     * Call it when a player's group just changed (e.g. a match started or ended).
     */
    public static void refreshViewGroups(Collection<? extends Player> players) {
        TabModule current = module;
        if (current != null && players != null && !players.isEmpty()) {
            current.refreshViewGroups(players);
        }
    }
}
