package net.mwtw.hippoNick.command;

import net.mwtw.hippoNick.gui.RealNameGuiService;
import net.mwtw.hippoNick.message.MessagePlaceholder;
import net.mwtw.hippoNick.message.Messages;
import net.mwtw.hippoNick.service.NetworkNickDirectory;
import net.mwtw.hippoNick.service.NickManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class RealNameCommand implements CommandExecutor, TabCompleter {
    private final NickManager nickManager;
    private final Messages messages;
    private final RealNameGuiService realNameGuiService;
    private final NetworkNickDirectory directory;

    public RealNameCommand(NickManager nickManager, Messages messages, RealNameGuiService realNameGuiService,
                           NetworkNickDirectory directory) {
        this.nickManager = nickManager;
        this.messages = messages;
        this.realNameGuiService = realNameGuiService;
        this.directory = directory;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hippotab.nick.realname")) {
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

        Optional<Player> local = nickManager.resolveRealPlayer(args[0]);
        if (local.isPresent()) {
            Player player = local.get();
            messages.send(sender, "realname.result",
                    MessagePlaceholder.of("display", nickManager.resolveDisplay(player).effectiveName()),
                    MessagePlaceholder.of("real", nickManager.realName(player)));
            return true;
        }
        // Not on this server: look among the nicked players of the other servers.
        Optional<NetworkNickDirectory.Entry> remote = directory.find(args[0]);
        if (remote.isPresent()) {
            messages.send(sender, "realname.result-network",
                    MessagePlaceholder.of("display", remote.get().nickname()),
                    MessagePlaceholder.of("real", remote.get().realName()),
                    MessagePlaceholder.of("server", remote.get().server()));
            return true;
        }
        messages.send(sender, "realname.not-found", MessagePlaceholder.of("query", args[0]));
        return true;
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
            if (display.toLowerCase(Locale.ROOT).startsWith(input) && !options.contains(display)) {
                options.add(display);
            }
        }
        for (NetworkNickDirectory.Entry entry : directory.remoteEntries()) {
            if (entry.nickname().toLowerCase(Locale.ROOT).startsWith(input) && !options.contains(entry.nickname())) {
                options.add(entry.nickname());
            }
        }
        return options;
    }
}
