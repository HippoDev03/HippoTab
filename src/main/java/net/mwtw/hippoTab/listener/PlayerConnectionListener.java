package net.mwtw.hippoTab.listener;

import net.mwtw.hippoTab.service.BelowNameService;
import net.mwtw.hippoTab.service.PlaceholderService;
import net.mwtw.hippoTab.service.RedisTabSyncService;
import net.mwtw.hippoTab.service.SidebarScoreboardService;
import net.mwtw.hippoTab.service.TabService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Tab/belowname/sidebar/redis connection handling. The overhead nametag is owned
 * by the nick subsystem (PacketNametagService) and handled by its own listener.
 */
public final class PlayerConnectionListener implements Listener {
    private final Plugin plugin;
    private final TabService tabService;
    private final BelowNameService belowNameService;
    private final SidebarScoreboardService sidebarScoreboardService;
    private final RedisTabSyncService redisTabSyncService;
    private final PlaceholderService placeholderService;

    public PlayerConnectionListener(Plugin plugin,
                                    TabService tabService,
                                    BelowNameService belowNameService,
                                    SidebarScoreboardService sidebarScoreboardService,
                                    RedisTabSyncService redisTabSyncService,
                                    PlaceholderService placeholderService) {
        this.plugin = plugin;
        this.tabService = tabService;
        this.belowNameService = belowNameService;
        this.sidebarScoreboardService = sidebarScoreboardService;
        this.redisTabSyncService = redisTabSyncService;
        this.placeholderService = placeholderService;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            tabService.removePlayer(event.getPlayer());
            tabService.refreshPlayer(event.getPlayer());
            tabService.refreshPlayerNextTick(event.getPlayer());
            tabService.applySorting();
            belowNameService.onPlayerJoin(event.getPlayer());
            sidebarScoreboardService.updatePlayer(event.getPlayer());
            redisTabSyncService.handleConnectionChange();
        });
    }

    @EventHandler
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Player player = event.getPlayer();
            belowNameService.onPlayerChangeWorld(player);
            tabService.refreshPlayer(player);
            sidebarScoreboardService.updatePlayer(player);
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        placeholderService.invalidate(event.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            tabService.removePlayer(event.getPlayer());
            belowNameService.removePlayer(event.getPlayer());
            sidebarScoreboardService.removePlayer(event.getPlayer());
            redisTabSyncService.handleConnectionChange();
        });
    }
}
