package net.mwtw.hippoTab.command;

import net.mwtw.hippoNick.Core;
import net.mwtw.hippoNick.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;

public final class HippoTabCommand implements CommandExecutor, TabCompleter {
    private final Core plugin;
    private final Messages messages;

    public HippoTabCommand(Core plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            messages.send(sender, "hippotab.usage");
            return true;
        }

        if (!sender.hasPermission("hippotab.reload")) {
            messages.send(sender, "command.no-permission");
            return true;
        }

        plugin.reloadRuntimeState();
        messages.send(sender, "hippotab.reloaded");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && "reload".startsWith(args[0].toLowerCase())) {
            return List.of("reload");
        }
        return Collections.emptyList();
    }
}
