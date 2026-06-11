package net.mwtw.hippoNick.command;

import net.mwtw.hippoNick.message.MessagePlaceholder;
import net.mwtw.hippoNick.message.Messages;
import net.mwtw.hippoNick.gui.RealNameGuiService;
import net.mwtw.hippoNick.service.NickManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RealNameCommand implements CommandExecutor, TabCompleter {
    private final NickManager nickManager;
    private final Messages messages;
    private final RealNameGuiService realNameGuiService;

    public RealNameCommand(NickManager nickManager, Messages messages, RealNameGuiService realNameGuiService) {
        this.nickManager = nickManager;
        this.messages = messages;
        this.realNameGuiService = realNameGuiService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hipponick.realname")) {
            messages.send(sender, "command.no-permission");
            return true;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                messages.send(sender, "command.player-only");
                return true;
            }
            realNameGuiService.open(player);
            return true;
        }
        if (args.length != 1) {
            messages.send(sender, "realname.usage");
            return true;
        }

        return nickManager.resolveRealPlayer(args[0])
                .map(player -> {
                    String effective = nickManager.resolveDisplay(player).effectiveName();
                    messages.send(sender, "realname.result",
                            MessagePlaceholder.of("display", effective),
                            MessagePlaceholder.of("real", player.getName()));
                    return true;
                })
                .orElseGet(() -> {
                    messages.send(sender, "realname.not-found", MessagePlaceholder.of("query", args[0]));
                    return true;
                });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String input = args[0].toLowerCase(Locale.ROOT);
        ArrayList<String> options = new ArrayList<>();
        for (String online : nickManager.onlinePlayerNames()) {
            if (online.toLowerCase(Locale.ROOT).startsWith(input)) {
                options.add(online);
            }
        }
        for (String display : nickManager.onlineDisplayNames()) {
            if (display.toLowerCase(Locale.ROOT).startsWith(input)) {
                options.add(display);
            }
        }
        return options;
    }
}
