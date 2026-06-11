package net.mwtw.hippoNick.papi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.mwtw.hippoNick.Core;
import net.mwtw.hippoNick.domain.DisplayState;
import net.mwtw.hippoNick.domain.NickProfile;
import net.mwtw.hippoNick.service.NickManager;
import net.mwtw.hippoNick.service.RankService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public final class HippoNickPlaceholderExpansion extends PlaceholderExpansion {
    private final Core plugin;
    private final NickManager nickManager;
    private final RankService rankService;

    public HippoNickPlaceholderExpansion(Core plugin, NickManager nickManager, RankService rankService) {
        this.plugin = plugin;
        this.nickManager = nickManager;
        this.rankService = rankService;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "hipponick";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (offlinePlayer == null || offlinePlayer.getUniqueId() == null) {
            return "";
        }
        Player player = Bukkit.getPlayer(offlinePlayer.getUniqueId());
        DisplayState display = player != null
                ? nickManager.resolveDisplay(player)
                : nickManager.resolveStoredDisplay(offlinePlayer.getUniqueId(), offlinePlayer.getName());
        Optional<NickProfile> profile = nickManager.findProfile(offlinePlayer.getUniqueId());

        return switch (params.toLowerCase()) {
            case "nickname" -> profile.map(NickProfile::nickname).filter(s -> !s.isBlank()).orElse("");
            case "realname" -> offlinePlayer.getName() == null ? "" : offlinePlayer.getName();
            case "rank" -> display.effectiveRank();
            case "weight", "rank_weight", "nick_weight" ->
                    rankService.resolveWeight(display.effectiveRank()).map(String::valueOf).orElse("0");
            case "prefix" -> rankService.resolvePrefix(display.effectiveRank()).orElse("");
            case "display_name" -> display.effectiveName();
            case "full_display" -> {
                String prefix = rankService.resolvePrefix(display.effectiveRank()).orElse("");
                yield (prefix + " " + display.effectiveName()).trim();
            }
            case "is_nicked" -> profile.map(NickProfile::nickname).filter(s -> !s.isBlank()).isPresent() ? "true" : "false";
            default -> null;
        };
    }
}
