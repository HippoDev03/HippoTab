package net.mwtw.hippoTab.listener;

import net.mwtw.hippoTab.service.BelowNameService;
import net.mwtw.hippoTab.service.ClientTeamStateService;
import net.mwtw.hippoTab.service.NameTagService;
import net.mwtw.hippoTab.service.PlaceholderService;
import net.mwtw.hippoTab.service.RedisTabSyncService;
import net.mwtw.hippoTab.service.SidebarScoreboardService;
import net.mwtw.hippoTab.service.TabService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerConnectionListener implements Listener {
    private final TabService tabService;
    private final NameTagService nameTagService;
    private final BelowNameService belowNameService;
    private final SidebarScoreboardService sidebarScoreboardService;
    private final ClientTeamStateService clientTeamStateService;
    private final RedisTabSyncService redisTabSyncService;

    public PlayerConnectionListener(TabService tabService,
                                    NameTagService nameTagService,
                                    BelowNameService belowNameService,
                                    SidebarScoreboardService sidebarScoreboardService,
                                    ClientTeamStateService clientTeamStateService,
                                    RedisTabSyncService redisTabSyncService,
                                    PlaceholderService placeholderService) {
        this.tabService = tabService;
        this.nameTagService = nameTagService;
        this.belowNameService = belowNameService;
        this.sidebarScoreboardService = sidebarScoreboardService;
        this.clientTeamStateService = clientTeamStateService;
        this.redisTabSyncService = redisTabSyncService;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        clientTeamStateService.clearAndPrepareReassign(event.getPlayer());
        tabService.removePlayer(event.getPlayer());
        tabService.refreshPlayer(event.getPlayer());
        tabService.refreshPlayerNextTick(event.getPlayer());
        tabService.applySorting();
        nameTagService.updatePlayer(event.getPlayer());
        nameTagService.updateAllNextTick();
        belowNameService.onPlayerJoin(event.getPlayer());
        sidebarScoreboardService.updatePlayer(event.getPlayer());
        redisTabSyncService.handleConnectionChange();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        tabService.removePlayer(event.getPlayer());
        nameTagService.removePlayer(event.getPlayer());
        belowNameService.removePlayer(event.getPlayer());
        sidebarScoreboardService.removePlayer(event.getPlayer());
        redisTabSyncService.handleConnectionChange();
    }
}
