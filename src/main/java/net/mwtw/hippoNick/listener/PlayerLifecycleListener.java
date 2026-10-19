package net.mwtw.hippoNick.listener;

import net.mwtw.hippoNick.Core;
import net.mwtw.hippoNick.message.Messages;
import net.mwtw.hippoNick.service.NickManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Nick-subsystem join/quit handling (profile cache warm-up, re-applying a stored
 * nick, refreshing nicked skins). The overhead nametag join/quit is handled by
 * {@link NametagLifecycleListener} so it works even when nick is disabled.
 */
public final class PlayerLifecycleListener implements Listener {
    private final Core plugin;
    private final NickManager nickManager;
    private final Messages messages;

    public PlayerLifecycleListener(Core plugin, NickManager nickManager, Messages messages) {
        this.plugin = plugin;
        this.nickManager = nickManager;
        this.messages = messages;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        // Warm the profile cache on the login thread, so the find() calls in the join
        // handler below (and on packet threads) never touch the storage backend.
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            nickManager.preloadProfile(event.getUniqueId());
        }
    }

    // LOWEST: the nick must be in place before any other plugin builds the join message
    // and before the player is listed for other clients, or the real name leaks.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        nickManager.rememberRealName(joined);
        for (Player cleared : nickManager.clearConflictingNicks(joined)) {
            messages.send(cleared, "nick.cleared");
        }
        nickManager.applyOnJoin(joined);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (joined.isOnline()) {
                if (nickManager.isNicked(joined)) {
                    nickManager.applyDisplay(joined);
                }
                nickManager.refreshNickedSkinsForViewer(joined);
            }
        }, 20L);
    }

    // MONITOR: other plugins' quit handlers still see the nicked player as they knew them.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        nickManager.restoreRealProfileNow(event.getPlayer());
        nickManager.clearRuntimeState(event.getPlayer().getUniqueId());
    }
}
