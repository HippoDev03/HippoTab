package net.mwtw.hippoTab.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Applies the custom chat format and drops viewers who have hidden public chat.
 * Runs at HIGH priority so it takes the final say on the render, while still leaving
 * MONITOR-priority loggers able to observe the event.
 */
public final class ChatListener implements Listener {
    private final ChatService chatService;

    public ChatListener(ChatService chatService) {
        this.chatService = chatService;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        // Remove players who toggled public chat off from the recipients (only while
        // the /togglechat feature is enabled on this server).
        if (chatService.isToggleEnabled()) {
            event.viewers().removeIf(viewer ->
                    viewer instanceof Player player && chatService.isChatHidden(player.getUniqueId()));
        }

        if (chatService.isFormatterEnabled()) {
            event.renderer((source, sourceDisplayName, message, viewer) -> chatService.format(source, message));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        chatService.clear(event.getPlayer().getUniqueId());
    }
}
