package net.mwtw.hippoNick.listener;

import net.mwtw.hippoNick.Core;
import net.mwtw.hippoNick.service.PacketNametagService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Drives overhead-nametag team creation/cleanup on join/quit. Registered whenever
 * the nametag feature is on — independent of the nick subsystem, so tab-only servers
 * (nick.enabled=false) still get rank nametags.
 */
public final class NametagLifecycleListener implements Listener {
    private final Core plugin;
    private final PacketNametagService nametagService;

    public NametagLifecycleListener(Core plugin, PacketNametagService nametagService) {
        this.plugin = plugin;
        this.nametagService = nametagService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (joined.isOnline()) {
                nametagService.onPlayerJoin(joined);
            }
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        nametagService.onPlayerQuit(event.getPlayer().getUniqueId());
    }
}
