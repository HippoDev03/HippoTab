package net.mwtw.hippoTab.command;

import net.mwtw.hippoNick.message.Messages;
import net.mwtw.hippoTab.pm.SpyService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * {@code /spy [off|server|network]} — chooses which private messages a staff member
 * sees. Without an argument it switches between off and "this server".
 */
public final class SpyCommand implements CommandExecutor, TabCompleter {
    private static final List<String> MODES = List.of("off", "server", "network");

    private final SpyService spyService;
    private final Messages messages;

    public SpyCommand(SpyService spyService, Messages messages) {
        this.spyService = spyService;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.player-only");
            return true;
        }
        if (!player.hasPermission(SpyService.PERMISSION)) {
            messages.send(player, "command.no-permission");
            return true;
        }
        if (!spyService.isEnabled()) {
            messages.send(player, "spy.disabled");
            return true;
        }

        SpyService.Mode current = spyService.mode(player.getUniqueId());
        SpyService.Mode next;
        if (args.length == 0) {
            next = current == SpyService.Mode.OFF ? SpyService.Mode.SERVER : SpyService.Mode.OFF;
        } else {
            next = SpyService.Mode.parse(args[0], null);
            if (next == null) {
                messages.send(player, "spy.usage");
                return true;
            }
        }
        if (next == SpyService.Mode.NETWORK && !spyService.isNetworkAvailable()) {
            messages.send(player, "spy.network-unavailable");
            next = SpyService.Mode.SERVER;
        }
        spyService.setMode(player.getUniqueId(), next);
        messages.send(player, switch (next) {
            case OFF -> "spy.mode-off";
            case SERVER -> "spy.mode-server";
            case NETWORK -> "spy.mode-network";
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission(SpyService.PERMISSION)) {
            return List.of();
        }
        String input = args[0].toLowerCase(Locale.ROOT);
        return MODES.stream().filter(mode -> mode.startsWith(input)).toList();
    }
}
