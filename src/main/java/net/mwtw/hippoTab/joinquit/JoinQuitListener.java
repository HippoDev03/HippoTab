package net.mwtw.hippoTab.joinquit;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Sets the join/quit broadcast message at NORMAL priority. Vanish plugins (e.g.
 * HippoStaff) may still clear it at a later priority for vanished players.
 */
public final class JoinQuitListener implements Listener {
    private final JoinQuitService service;

    public JoinQuitListener(JoinQuitService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        event.joinMessage(service.joinMessage(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(service.quitMessage(event.getPlayer()));
    }
}
