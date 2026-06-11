package net.mwtw.hippoNick.command;

import net.mwtw.hippoNick.Core;
import net.mwtw.hippoNick.message.Messages;
import net.mwtw.hippoNick.gui.NickGuiService;
import net.mwtw.hippoNick.service.NickManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class NickCommand implements CommandExecutor, TabCompleter {
    private static final Pattern NAMEMC_IMAGE_PATH = Pattern.compile("^/i/([0-9a-fA-F]{32,64})\\.png$");
    private static final Pattern VALID_SKIN_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private final Core plugin;
    private final NickManager nickManager;
    private final NickGuiService nickGuiService;
    private final Messages messages;

    public NickCommand(Core plugin, NickManager nickManager, NickGuiService nickGuiService, Messages messages) {
        this.plugin = plugin;
        this.nickManager = nickManager;
        this.nickGuiService = nickGuiService;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.player-only");
            return true;
        }
        if (!player.hasPermission("hipponick.use")) {
            messages.send(player, "command.no-permission");
            return true;
        }
        if (command.getName().equalsIgnoreCase("unnick")) {
            if (!nickManager.isNicked(player)) {
                messages.send(player, "nick.already-cleared");
                return true;
            }
            nickManager.clear(player);
            messages.send(player, "nick.cleared");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("clear")) {
            if (!nickManager.isNicked(player)) {
                messages.send(player, "nick.already-cleared");
                return true;
            }
            nickManager.clear(player);
            messages.send(player, "nick.cleared");
            return true;
        }
        if (args.length == 0) {
            messages.sendList(player, "nick.help");
            return true;
        }
        if (args.length > 2) {
            messages.send(player, "nick.usage");
            return true;
        }
        String nickname = args[0].trim();
        if (!NickManager.isValidEnglishNickname(nickname)) {
            messages.send(player, "nick.invalid-length");
            return true;
        }
        if (nickManager.conflictsWithOnlineRealName(player, nickname)) {
            messages.send(player, "nick.blocked-online-name");
            return true;
        }
        String skinSource = null;
        if (args.length == 2) {
            skinSource = args[1].trim();
            // Validate the skin token up front (cheap), but defer opening the GUI until
            // after the premium-name guard below.
            if (parseSkinUrl(skinSource) == null && !VALID_SKIN_NAME.matcher(skinSource).matches()) {
                messages.send(player, "nick.invalid-skin");
                return true;
            }
        }
        final String finalSkinSource = skinSource;
        guardPremiumName(player, nickname, () -> openNickFlow(player, nickname, finalSkinSource));
        return true;
    }

    /**
     * Blocks nicknames that match a REAL premium Mojang account, so players cannot
     * impersonate genuine accounts. Players with {@code hipponick.nick.premium} (OP by
     * default) bypass this, as does disabling {@code nick.block-premium-names}. The
     * Mojang lookup is async; on lookup failure we fail open (allow) so an outage does
     * not lock everyone out of nicking.
     */
    private void guardPremiumName(Player player, String nickname, Runnable onAllowed) {
        if (!plugin.getConfig().getBoolean("nick.block-premium-names", true)
                || player.hasPermission("hipponick.nick.premium")) {
            onAllowed.run();
            return;
        }
        PlayerProfile lookup = Bukkit.createProfile(nickname);
        lookup.update().thenAccept(updated -> Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(player.getUniqueId());
            if (online == null || !online.isOnline()) {
                return;
            }
            if (updated != null && updated.getUniqueId() != null) {
                messages.send(online, "nick.blocked-premium-name");
                return;
            }
            onAllowed.run();
        })).exceptionally(error -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(player.getUniqueId());
                if (online != null && online.isOnline()) {
                    onAllowed.run();
                }
            });
            return null;
        });
    }

    /** Opens the rank selector (and skin validation when a skin token was supplied). */
    private void openNickFlow(Player player, String nickname, String skinSource) {
        if (skinSource == null) {
            nickGuiService.openRankSelector(player, nickname, null);
            return;
        }
        URL skinUrl = parseSkinUrl(skinSource);
        if (skinUrl != null) {
            nickGuiService.openRankSelector(player, nickname, skinUrl.toExternalForm());
            return;
        }
        validateSkinAndOpenGui(player, nickname, skinSource);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("nick") && args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            if ("clear".startsWith(input)) {
                return List.of("clear");
            }
        }
        return List.of();
    }

    private void validateSkinAndOpenGui(Player player, String nickname, String skinName) {
        PlayerProfile lookup = Bukkit.createProfile(skinName);
        lookup.update().thenAccept(updated ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(player.getUniqueId());
                    if (online == null || !online.isOnline()) {
                        return;
                    }
                    if (!hasSkinTexture(updated)) {
                        messages.send(online, "nick.skin-not-found");
                        return;
                    }
                    nickGuiService.openRankSelector(online, nickname, skinName);
                })).exceptionally(error -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(player.getUniqueId());
                if (online == null || !online.isOnline()) {
                    return;
                }
                messages.send(online, "nick.skin-not-found");
            });
            return null;
        });
    }

    private boolean hasSkinTexture(PlayerProfile profile) {
        return profile != null
                && profile.getTextures() != null
                && profile.getTextures().getSkin() != null;
    }

    private URL parseSkinUrl(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            String normalized = raw.trim().replaceAll("(?i)%C2%B7+$", "");
            while (normalized.endsWith("·")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            URI uri = new URI(normalized);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if ("s.namemc.com".equals(host)) {
                java.util.regex.Matcher matcher = NAMEMC_IMAGE_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
                if (matcher.matches()) {
                    return URI.create("https://textures.minecraft.net/texture/" + matcher.group(1)).toURL();
                }
            }
            return uri.toURL();
        } catch (URISyntaxException | MalformedURLException exception) {
            return null;
        }
    }
}
