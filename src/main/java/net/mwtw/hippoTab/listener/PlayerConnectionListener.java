package net.mwtw.hippoTab.listener;

import net.mwtw.hippoTab.service.BelowNameService;
import net.mwtw.hippoTab.service.ClientTeamStateService;
import net.mwtw.hippoTab.service.NameTagService;
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

public final class PlayerConnectionListener implements Listener {
    private final Plugin plugin;
    private final TabService tabService;
    private final NameTagService nameTagService;
    private final BelowNameService belowNameService;
    private final SidebarScoreboardService sidebarScoreboardService;
    private final ClientTeamStateService clientTeamStateService;
    private final RedisTabSyncService redisTabSyncService;

    public PlayerConnectionListener(Plugin plugin,
                                    TabService tabService,
                                    NameTagService nameTagService,
                                    BelowNameService belowNameService,
                                    SidebarScoreboardService sidebarScoreboardService,
                                    ClientTeamStateService clientTeamStateService,
                                    RedisTabSyncService redisTabSyncService,
                                    PlaceholderService placeholderService) {
        this.plugin = plugin;
        this.tabService = tabService;
        this.nameTagService = nameTagService;
        this.belowNameService = belowNameService;
        this.sidebarScoreboardService = sidebarScoreboardService;
        this.clientTeamStateService = clientTeamStateService;
        this.redisTabSyncService = redisTabSyncService;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            clientTeamStateService.clearAndPrepareReassign(event.getPlayer());
            tabService.removePlayer(event.getPlayer());
            tabService.refreshPlayer(event.getPlayer());
            tabService.refreshPlayerNextTick(event.getPlayer());
            tabService.applySorting();
            nameTagService.onPlayerJoin(event.getPlayer());
            nameTagService.updatePlayer(event.getPlayer());
            nameTagService.updateAllNextTick();
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
            nameTagService.updatePlayer(player);
            sidebarScoreboardService.updatePlayer(player);
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            tabService.removePlayer(event.getPlayer());
            nameTagService.removePlayer(event.getPlayer());
            belowNameService.removePlayer(event.getPlayer());
            sidebarScoreboardService.removePlayer(event.getPlayer());
            redisTabSyncService.handleConnectionChange();
        });
    }
}
