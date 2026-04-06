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
    int defaultRank
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

        return new TabConfig(
            updateIntervalTicks,
            headerLines,
            footerLines,
            playerListNameFormat,
            sortingEnabled,
            rankPlaceholder,
            sortingDescending,
            defaultRank
        );
    }
}
