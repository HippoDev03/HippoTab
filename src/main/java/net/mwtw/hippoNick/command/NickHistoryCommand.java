package net.mwtw.hippoNick.command;

import net.mwtw.hippoNick.history.NickHistoryService;
import net.mwtw.hippoNick.message.MessagePlaceholder;
import net.mwtw.hippoNick.message.Messages;
import net.mwtw.hippoNick.service.NickManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * {@code /nickhistory <player|nickname> [page]} — shows the recorded nick changes of a
 * player (online or offline, by real name), or who has used a given nickname.
 */
public final class NickHistoryCommand implements CommandExecutor, TabCompleter {
    private static final int PAGE_SIZE = 8;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final NickHistoryService history;
    private final NickManager nickManager;
    private final Messages messages;

    public NickHistoryCommand(NickHistoryService history, NickManager nickManager, Messages messages) {
        this.history = history;
        this.nickManager = nickManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hippotab.nick.history")) {
            messages.send(sender, "command.no-permission");
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            messages.send(sender, "nickhistory.usage");
            return true;
        }
        int page = 1;
        if (args.length == 2) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException exception) {
                messages.send(sender, "nickhistory.usage");
                return true;
            }
        }
        String query = args[0];
        int shownPage = page;
        // One extra row tells us whether there is a next page.
        history.lookup(query, (page - 1) * PAGE_SIZE, PAGE_SIZE + 1, entries -> show(sender, query, shownPage, entries));
        return true;
    }

    private void show(CommandSender sender, String query, int page, List<NickHistoryService.Entry> entries) {
        if (entries.isEmpty()) {
            messages.send(sender, "nickhistory.empty", MessagePlaceholder.of("query", query));
            return;
        }
        messages.send(sender, "nickhistory.header",
                MessagePlaceholder.of("query", query), MessagePlaceholder.of("page", page));
        int shown = Math.min(PAGE_SIZE, entries.size());
        for (int i = 0; i < shown; i++) {
            NickHistoryService.Entry entry = entries.get(i);
            String key = switch (entry.action()) {
                case NickHistoryService.SET -> "nickhistory.entry-set";
                case NickHistoryService.RANK -> "nickhistory.entry-rank";
                default -> "nickhistory.entry-clear";
            };
            messages.send(sender, key,
                    MessagePlaceholder.of("time", TIME.format(Instant.ofEpochMilli(entry.time()))),
                    MessagePlaceholder.of("player", entry.realName()),
                    MessagePlaceholder.of("nickname", dash(entry.nickname())),
                    MessagePlaceholder.of("rank", dash(entry.rank())),
                    MessagePlaceholder.of("skin", dash(entry.skin())),
                    MessagePlaceholder.of("actor", dash(entry.actor())),
                    MessagePlaceholder.of("server", dash(entry.server())));
        }
        if (entries.size() > PAGE_SIZE) {
            messages.send(sender, "nickhistory.more",
                    MessagePlaceholder.of("query", query), MessagePlaceholder.of("page", page + 1));
        }
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission("hippotab.nick.history")) {
            return List.of();
        }
        String input = args[0].toLowerCase(Locale.ROOT);
        return nickManager.onlinePlayerNames().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(input))
                .toList();
    }
}
