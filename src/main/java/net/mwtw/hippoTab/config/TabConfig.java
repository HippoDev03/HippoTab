package net.mwtw.hippoTab.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    String belownameTitle,
    String belownameValue,
    String belownameFancyValue,
    String belownameFancyValueDefault,
    String belownameDisableCondition,
    boolean scoreboardEnabled,
    long scoreboardUpdateIntervalTicks,
    boolean scoreboardHideNumber,
    String scoreboardTitle,
    List<String> scoreboardLines,
    RedisSyncConfig redisSync,
    Map<String, ConditionalPlaceholderConfig> conditionalPlaceholders
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
        String belownameTitle = config.getString(
            "belowname.title",
            config.getString("belowname.format", "<red>❤")
        );
        String belownameValue = config.getString("belowname.value", "%player_health%");
        String belownameFancyValue = config.getString("belowname.fancy-value", "<red>%player_health%");
        String belownameFancyValueDefault = config.getString("belowname.fancy-value-default", "<gray>NPC");
        String belownameDisableCondition = config.getString(
            "belowname.disable-condition",
            config.getString("belowname.disable-if", "")
        );
        boolean scoreboardEnabled = config.getBoolean("scoreboard.enabled", false);
        long scoreboardUpdateIntervalTicks = Math.max(1L, config.getLong("scoreboard.update-interval-ticks", 20L));
        boolean scoreboardHideNumber = config.getBoolean("scoreboard.hide-number", false);
        String scoreboardTitle = config.getString("scoreboard.title", "<aqua><bold>HippoTab</bold>");
        List<String> scoreboardLines = config.getStringList("scoreboard.lines");
        RedisSyncConfig redisSync = RedisSyncConfig.from(plugin);
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
            belownameTitle,
            belownameValue,
            belownameFancyValue,
            belownameFancyValueDefault,
            belownameDisableCondition,
            scoreboardEnabled,
            scoreboardUpdateIntervalTicks,
            scoreboardHideNumber,
            scoreboardTitle,
            scoreboardLines,
            redisSync,
            Map.of()
        );
    }

    public TabConfig withConditionalPlaceholders(ConfigurationSection config) {
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
            belownameTitle,
            belownameValue,
            belownameFancyValue,
            belownameFancyValueDefault,
            belownameDisableCondition,
            scoreboardEnabled,
            scoreboardUpdateIntervalTicks,
            scoreboardHideNumber,
            scoreboardTitle,
            scoreboardLines,
            redisSync,
            loadConditionalPlaceholders(config)
        );
    }

    private static Map<String, ConditionalPlaceholderConfig> loadConditionalPlaceholders(ConfigurationSection config) {
        Map<String, ConditionalPlaceholderConfig> placeholders = new LinkedHashMap<>();

        for (String key : config.getKeys(false)) {
            if (!config.isConfigurationSection(key)) {
                continue;
            }

            var section = config.getConfigurationSection(key);
            if (section == null) {
                continue;
            }

            ConditionalPlaceholderConfig conditionalPlaceholder = ConditionalPlaceholderConfig.from(section);
            if (conditionalPlaceholder.isValid()) {
                placeholders.put(key, conditionalPlaceholder);
            }
        }

        return Map.copyOf(placeholders);
    }
}
