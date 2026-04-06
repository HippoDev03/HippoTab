package net.mwtw.hippoTab.listener;

import net.mwtw.hippoTab.service.TabService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerConnectionListener implements Listener {
    private final TabService tabService;

    public PlayerConnectionListener(TabService tabService) {
        this.tabService = tabService;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        tabService.refreshPlayer(event.getPlayer());
        tabService.applySorting();
    }
}
