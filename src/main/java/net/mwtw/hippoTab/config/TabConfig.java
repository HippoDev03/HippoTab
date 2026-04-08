package net.mwtw.hippoTab.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public record TabConfig(
    long updateIntervalTicks,
    List<String> headerLines,
    List<String> footerLines,
    String playerListNameFormat,
    boolean sortingEnabled,
    String rankPlaceholder,
    boolean sortingDescending,
    int defaultRank,
    boolean nametagEnabled,
    boolean nametagAutoAssignTeam,
    long nametagUpdateIntervalTicks,
    String nametagPrefix,
    String nametagSuffix,
    String nametagDisableIf,
    boolean belownameEnabled,
    long belownameUpdateIntervalTicks,
    String belownameFormat,
    String belownameValue,
    String belownameDisableIf
) {
    public static TabConfig from(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();

        long updateIntervalTicks = Math.max(1L, config.getLong("update-interval-ticks", 40L));
        List<String> headerLines = config.getStringList("tab.header");
        List<String> footerLines = config.getStringList("tab.footer");
        String playerListNameFormat = config.getString("tab.player-list-name", "<gray>%player_name%</gray>");

        boolean sortingEnabled = config.getBoolean("sorting.enabled", true);
        String rankPlaceholder = config.getString("sorting.rank-placeholder", "%vaultunlocked_weight%");
        boolean sortingDescending = config.getBoolean("sorting.descending", true);
        int defaultRank = config.getInt("sorting.default-rank", 0);

        boolean nametagEnabled = config.getBoolean("nametag.enabled", true);
        boolean nametagAutoAssignTeam = config.getBoolean("nametag.auto-assign-team", false);
        long nametagUpdateIntervalTicks = Math.max(1L, config.getLong("nametag.update-interval-ticks", 40L));
        String nametagPrefix = config.getString("nametag.prefix", "%vaultunlocked_prefix%");
        String nametagSuffix = config.getString("nametag.suffix", "");
        String nametagDisableIf = config.getString("nametag.disable-if", "");

        boolean belownameEnabled = config.getBoolean("belowname.enabled", true);
        long belownameUpdateIntervalTicks = Math.max(1L, config.getLong("belowname.update-interval-ticks", 20L));
        String belownameFormat = config.getString("belowname.format", "<red>❤");
        String belownameValue = config.getString("belowname.value", "%player_health%");
        String belownameDisableIf = config.getString("belowname.disable-if", "");

        return new TabConfig(
            updateIntervalTicks,
            headerLines,
            footerLines,
            playerListNameFormat,
            sortingEnabled,
            rankPlaceholder,
            sortingDescending,
            defaultRank,
            nametagEnabled,
            nametagAutoAssignTeam,
            nametagUpdateIntervalTicks,
            nametagPrefix,
            nametagSuffix,
            nametagDisableIf,
            belownameEnabled,
            belownameUpdateIntervalTicks,
            belownameFormat,
            belownameValue,
            belownameDisableIf
        );
    }
}
