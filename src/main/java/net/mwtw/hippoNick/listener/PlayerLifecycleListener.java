package net.mwtw.hippoNick.listener;

import net.mwtw.hippoNick.Core;
import net.mwtw.hippoNick.service.NickManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
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

    public PlayerLifecycleListener(Core plugin, NickManager nickManager) {
        this.plugin = plugin;
        this.nickManager = nickManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        // Warm the profile cache off the main thread so the first find() (which may
        // hit MariaDB) never blocks the main thread or an early packet thread.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                nickManager.preloadProfile(joined.getUniqueId()));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (joined.isOnline()) {
                if (nickManager.isNicked(joined)) {
                    nickManager.applyDisplay(joined);
                }
                nickManager.refreshNickedSkinsForViewer(joined);
            }
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        nickManager.clearRuntimeState(event.getPlayer().getUniqueId());
    }
}
