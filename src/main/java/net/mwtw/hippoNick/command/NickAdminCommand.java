package net.mwtw.hippoNick.command;

import net.mwtw.hippoNick.Core;
import net.mwtw.hippoNick.message.MessagePlaceholder;
import net.mwtw.hippoNick.message.Messages;
import net.mwtw.hippoNick.service.NickManager;
import net.mwtw.hippoNick.service.RankService;
import net.mwtw.hippoNick.storage.CachingNickRepository;
import net.mwtw.hippoNick.storage.MariaDbNickRepository;
import net.mwtw.hippoNick.storage.NickRepository;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class NickAdminCommand implements CommandExecutor, TabCompleter {
    private static final Pattern VALID_SKIN_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private final Core plugin;
    private final NickManager nickManager;
    private final RankService rankService;
    private final NickRepository repository;
    private final Messages messages;

    public NickAdminCommand(Core plugin, NickManager nickManager, RankService rankService, NickRepository repository, Messages messages) {
        this.plugin = plugin;
        this.nickManager = nickManager;
        this.rankService = rankService;
        this.repository = repository;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            messages.send(sender, "nickadmin.usage");
            return true;
        }

        if (args[0].equalsIgnoreCase("setrank")) {
            return handleSetRank(sender, args);
        }
        if (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("add")) {
            return handleSet(sender, args);
        }
        if (args[0].equalsIgnoreCase("remove")) {
            return handleRemove(sender, args);
        }
        if (args[0].equalsIgnoreCase("migratestorage")) {
            return handleMigrate(sender);
        }
        if (args[0].equalsIgnoreCase("reload")) {
            return handleReload(sender);
        }

        messages.send(sender, "nickadmin.unknown-subcommand");
        return true;
    }

    private boolean handleSetRank(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hippotab.nick.admin")) {
            messages.send(sender, "command.no-permission");
            return true;
        }
        if (args.length != 3) {
            messages.send(sender, "nickadmin.setrank.usage");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(sender, "nickadmin.setrank.player-not-found", MessagePlaceholder.of("player", args[1]));
            return true;
        }

        boolean success = nickManager.setRankOverride(target, args[2], actor(sender));
        if (!success) {
            messages.send(sender, "nickadmin.setrank.unknown-rank", MessagePlaceholder.of("rank", args[2]));
            return true;
        }
        messages.send(sender, "nickadmin.setrank.success",
                MessagePlaceholder.of("player", nickManager.realName(target)),
                MessagePlaceholder.of("rank", args[2]));
        return true;
    }

    private boolean handleMigrate(CommandSender sender) {
        if (!sender.hasPermission("hippotab.nick.admin")) {
            messages.send(sender, "command.no-permission");
            return true;
        }
        if (!(backend() instanceof MariaDbNickRepository)) {
            messages.send(sender, "nickadmin.migrate.requires-mariadb");
            return true;
        }

        String fileName = plugin.getConfig().getString("storage.file.name", "nick-data.yml");
        Path filePath = plugin.getDataFolder().toPath().resolve(fileName);
        int migrated = nickManager.migrateFromFile(filePath);
        messages.send(sender, "nickadmin.migrate.success", MessagePlaceholder.of("count", migrated));
        return true;
    }

    /** Name recorded in the nick history for whoever ran the command. */
    private String actor(CommandSender sender) {
        return sender instanceof Player player ? nickManager.realName(player) : sender.getName();
    }

    private NickRepository backend() {
        return repository instanceof CachingNickRepository caching ? caching.delegate() : repository;
    }

    private boolean handleSet(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hippotab.nick.admin")) {
            messages.send(sender, "command.no-permission");
            return true;
        }
        if (args.length < 3 || args.length > 5) {
            messages.send(sender, "nickadmin.set.usage");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(sender, "nickadmin.setrank.player-not-found", MessagePlaceholder.of("player", args[1]));
            return true;
        }
        String nickname = args[2].trim();
        if (!NickManager.isValidEnglishNickname(nickname)) {
            messages.send(sender, "nick.invalid-length");
            return true;
        }
        if (nickManager.conflictsWithOnlineRealName(target, nickname)) {
            messages.send(sender, "nick.blocked-online-name");
            return true;
        }

        String rank = null;
        if (args.length >= 4) {
            rank = args[3];
            if (rankService.findCanonicalRank(rank).isEmpty()) {
                messages.send(sender, "nickadmin.setrank.unknown-rank", MessagePlaceholder.of("rank", rank));
                return true;
            }
        }

        String skin = null;
        if (args.length == 5) {
            skin = args[4].trim();
            URL skinUrl = parseSkinUrl(skin);
            if (skinUrl != null) {
                skin = skinUrl.toExternalForm();
            } else if (!VALID_SKIN_NAME.matcher(skin).matches()) {
                messages.send(sender, "nick.invalid-skin");
                return true;
            }
        }

        // Overwrites any existing nick — no need to /nickadmin remove first.
        nickManager.setNicknameAndRank(target, nickname, rank, skin, actor(sender));
        messages.send(sender, "nickadmin.set.success",
                MessagePlaceholder.of("player", nickManager.realName(target)),
                MessagePlaceholder.of("nickname", nickname));
        return true;
    }

    private boolean handleRemove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hippotab.nick.admin")) {
            messages.send(sender, "command.no-permission");
            return true;
        }
        if (args.length != 2) {
            messages.send(sender, "nickadmin.remove.usage");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(sender, "nickadmin.setrank.player-not-found", MessagePlaceholder.of("player", args[1]));
            return true;
        }
        if (!nickManager.isNicked(target)) {
            messages.send(sender, "nickadmin.remove.already-clear", MessagePlaceholder.of("player", nickManager.realName(target)));
            return true;
        }
        nickManager.clear(target, actor(sender));
        messages.send(sender, "nickadmin.remove.success", MessagePlaceholder.of("player", nickManager.realName(target)));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("hippotab.nick.admin")) {
            messages.send(sender, "command.no-permission");
            return true;
        }
        plugin.reloadRuntimeState();
        messages.send(sender, "nickadmin.reload.success");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("setrank", "set", "remove", "migratestorage", "reload");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("setrank")
                || args[0].equalsIgnoreCase("set")
                || args[0].equalsIgnoreCase("add")
                || args[0].equalsIgnoreCase("remove"))) {
            String input = args[1].toLowerCase(Locale.ROOT);
            ArrayList<String> out = new ArrayList<>();
            for (String name : nickManager.onlinePlayerNames()) {
                if (name.toLowerCase(Locale.ROOT).startsWith(input)) {
                    out.add(name);
                }
            }
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("setrank")) {
            String input = args[2].toLowerCase(Locale.ROOT);
            return rankService.listRanks().stream()
                    .map(rank -> rank.name())
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(input))
                    .toList();
        }
        if (args.length == 4 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("add"))) {
            String input = args[3].toLowerCase(Locale.ROOT);
            return rankService.listRanks().stream()
                    .map(rank -> rank.name())
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(input))
                    .toList();
        }
        return List.of();
    }

    private URL parseSkinUrl(String raw) {
        return NickManager.parseSkinUrl(raw);
    }
}
