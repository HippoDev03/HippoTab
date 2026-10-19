package net.mwtw.hippoTab.command;

import net.mwtw.hippoNick.message.Messages;
import net.mwtw.hippoTab.chat.ChatService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * {@code /togglechat} — hides or shows public chat for the sending player.
 */
public final class ToggleChatCommand implements CommandExecutor, TabCompleter {
    private final ChatService chatService;
    private final Messages messages;

    public ToggleChatCommand(ChatService chatService, Messages messages) {
        this.chatService = chatService;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.player-only");
            return true;
        }

        if (!chatService.isToggleEnabled()) {
            messages.send(player, "togglechat.disabled");
            return true;
        }

        String permission = chatService.togglePermission();
        if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
            messages.send(player, "command.no-permission");
            return true;
        }

        boolean hidden = chatService.toggleHidden(player.getUniqueId());
        messages.send(player, hidden ? "togglechat.hidden" : "togglechat.shown");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
