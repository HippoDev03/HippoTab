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
    private static volatile net.mwtw.hippoNick.service.NickManager nickManager;

    private HippoTabApi() {
    }

    /** Called by HippoTab itself on enable/disable. */
    public static void bind(TabModule tabModule) {
        module = tabModule;
    }

    /** Called by HippoTab itself on enable/disable (null to unbind). Only bound when nick.enabled is true. */
    public static void bindNick(net.mwtw.hippoNick.service.NickManager nick) {
        nickManager = nick;
    }

    /**
     * The name other plugins should SHOW for this player right now — the nickname while
     * nicked, otherwise their real name. Other plugins must use this instead of
     * {@link Player#getName()}: setting a nick rewrites what the client is told (tab list,
     * overhead nametag, chat), but does not change what {@code getName()} returns on this
     * Paper version — that call always returns the real login name, nicked or not.
     *
     * <p>Falls back to {@code player.getName()} when the nick module is off (nick.enabled=false)
     * or the player has no nick set.</p>
     */
    public static String displayNameOf(Player player) {
        net.mwtw.hippoNick.service.NickManager current = nickManager;
        return current == null || player == null ? (player == null ? null : player.getName())
                : current.resolvePacketProfileName(player);
    }

    /** The nickname this player currently has set, or null when they have none / the nick module is off. */
    public static String nicknameOf(Player player) {
        net.mwtw.hippoNick.service.NickManager current = nickManager;
        return current == null || player == null ? null : current.nicknameOf(player);
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
