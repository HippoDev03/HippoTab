package net.mwtw.hippoTab.command;

import net.mwtw.hippoTab.Core;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;

public final class HippoTabCommand implements CommandExecutor, TabCompleter {
    private final Core plugin;

    public HippoTabCommand(Core plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(Component.text("Usage: /" + label + " reload", NamedTextColor.RED));
            return true;
        }

        if (!sender.hasPermission("hippotab.reload")) {
            sender.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
            return true;
        }

        plugin.reloadPluginState();
        sender.sendMessage(Component.text("HippoTab config reloaded.", NamedTextColor.GREEN));
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
