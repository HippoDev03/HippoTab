package net.mwtw.hippoTab.listener;

import net.mwtw.hippoTab.service.BelowNameService;
import net.mwtw.hippoTab.service.NameTagService;
import net.mwtw.hippoTab.service.TabService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerConnectionListener implements Listener {
    private final TabService tabService;
    private final NameTagService nameTagService;
    private final BelowNameService belowNameService;

    public PlayerConnectionListener(TabService tabService, NameTagService nameTagService, BelowNameService belowNameService) {
        this.tabService = tabService;
        this.nameTagService = nameTagService;
        this.belowNameService = belowNameService;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        tabService.refreshPlayer(event.getPlayer());
        tabService.applySorting();
        nameTagService.updatePlayer(event.getPlayer());
        belowNameService.updatePlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        nameTagService.removePlayer(event.getPlayer());
        belowNameService.removePlayer(event.getPlayer());
    }
}
