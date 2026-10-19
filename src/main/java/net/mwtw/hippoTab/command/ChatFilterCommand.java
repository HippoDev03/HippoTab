package net.mwtw.hippoTab.command;

import net.mwtw.hippoNick.message.MessagePlaceholder;
import net.mwtw.hippoNick.message.Messages;
import net.mwtw.hippoTab.chatfilter.FilterManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /hchat <reload|add|remove|list|allow|unallow|allowed|status|test>} — manages
 * the chat filter.
 */
public final class ChatFilterCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS =
            List.of("reload", "add", "remove", "list", "allow", "unallow", "allowed", "status", "test");

    private final JavaPlugin plugin;
    private final FilterManager filterManager;
    private final Messages messages;

    public ChatFilterCommand(JavaPlugin plugin, FilterManager filterManager, Messages messages) {
        this.plugin = plugin;
        this.filterManager = filterManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hippotab.chatfilter.admin")) {
            messages.send(sender, "command.no-permission");
            return true;
        }
        if (args.length == 0) {
            messages.sendList(sender, "chatfilter.help");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length < 2 && List.of("add", "remove", "allow", "unallow", "test").contains(sub)) {
            messages.sendList(sender, "chatfilter.help");
            return true;
        }
        switch (sub) {
            case "reload" -> {
                plugin.reloadConfig();
                messages.load();
                filterManager.reload();
                messages.send(sender, "chatfilter.reloaded");
            }
            case "add" -> messages.send(sender,
                    filterManager.addCustomWord(args[1]) ? "chatfilter.word-added" : "chatfilter.word-exists",
                    MessagePlaceholder.of("word", args[1]));
            case "remove" -> messages.send(sender,
                    filterManager.removeCustomWord(args[1]) ? "chatfilter.word-removed" : "chatfilter.word-not-found",
                    MessagePlaceholder.of("word", args[1]));
            case "list" -> sendEntries(sender, filterManager.getCustomWords(),
                    "chatfilter.word-list", "chatfilter.word-list-empty");
            case "allow" -> {
                String phrase = joinFrom(args, 1);
                messages.send(sender,
                        filterManager.addAllowedPhrase(phrase) ? "chatfilter.allow-added" : "chatfilter.allow-exists",
                        MessagePlaceholder.of("word", phrase));
            }
            case "unallow" -> {
                String phrase = joinFrom(args, 1);
                messages.send(sender,
                        filterManager.removeAllowedPhrase(phrase) ? "chatfilter.allow-removed" : "chatfilter.allow-not-found",
                        MessagePlaceholder.of("word", phrase));
            }
            case "allowed" -> sendEntries(sender, filterManager.getAllowedPhrases(),
                    "chatfilter.allow-list", "chatfilter.allow-list-empty");
            case "status" -> messages.send(sender, "chatfilter.status",
                    MessagePlaceholder.of("enabled", filterManager.isEnabled()),
                    MessagePlaceholder.of("count", filterManager.getWordCount()));
            case "test" -> {
                String input = joinFrom(args, 1);
                messages.send(sender, "chatfilter.test",
                        MessagePlaceholder.of("input", input),
                        MessagePlaceholder.of("output", filterManager.filterText(input)));
            }
            default -> messages.sendList(sender, "chatfilter.help");
        }
        return true;
    }

    private void sendEntries(CommandSender sender, List<String> entries, String listKey, String emptyKey) {
        if (entries.isEmpty()) {
            messages.send(sender, emptyKey);
            return;
        }
        messages.send(sender, listKey,
                MessagePlaceholder.of("count", entries.size()),
                MessagePlaceholder.of("words", String.join(", ", entries)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("hippotab.chatfilter.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(option -> option.startsWith(input)).toList();
        }
        if (args.length == 2 && "unallow".equalsIgnoreCase(args[0])) {
            return startingWith(filterManager.getAllowedPhrases(), args[1]);
        }
        if (args.length == 2 && "remove".equalsIgnoreCase(args[0])) {
            return startingWith(filterManager.getCustomWords(), args[1]);
        }
        return List.of();
    }

    private static List<String> startingWith(List<String> entries, String typed) {
        String input = typed.toLowerCase(Locale.ROOT);
        return entries.stream().filter(entry -> entry.toLowerCase(Locale.ROOT).startsWith(input)).toList();
    }

    private static String joinFrom(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }
}
