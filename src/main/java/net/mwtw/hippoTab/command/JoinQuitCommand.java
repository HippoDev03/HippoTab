package net.mwtw.hippoTab.command;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.mwtw.hippoNick.message.Messages;
import net.mwtw.hippoTab.joinquit.JoinQuitService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * {@code /connmsg <toggle|status>} — lets a player switch their own join/quit messages
 * on or off (both together), or view the current state.
 */
public final class JoinQuitCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("toggle", "status");

    private final JoinQuitService service;
    private final Messages messages;

    public JoinQuitCommand(JoinQuitService service, Messages messages) {
        this.service = service;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.player-only");
            return true;
        }

        String permission = service.togglePermission();
        if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
            messages.send(player, "command.no-permission");
            return true;
        }

        String sub = args.length == 0 ? "toggle" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "toggle", "join", "quit" -> {
                // Join and quit are one combined switch now; the old "join"/"quit"
                // sub-commands stay accepted and simply flip both together.
                boolean enabled = service.preferences().toggleBoth(player.getUniqueId());
                player.sendMessage(messages.component("connmsg.toggled", stateResolver(enabled)));
            }
            case "status" -> {
                // Join and quit move together, so a single state reflects both.
                boolean enabled = service.preferences().isJoinEnabled(player.getUniqueId())
                        && service.preferences().isQuitEnabled(player.getUniqueId());
                player.sendMessage(messages.component("connmsg.status", stateResolver(enabled)));
            }
            default -> messages.send(player, "connmsg.usage");
        }
        return true;
    }

    private TagResolver stateResolver(boolean enabled) {
        return Placeholder.component("state", messages.component(enabled ? "connmsg.state-on" : "connmsg.state-off"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return Collections.emptyList();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream()
                .filter(option -> option.startsWith(prefix))
                .collect(Collectors.toList());
    }
}
