package net.mwtw.hippoTab.service;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.mwtw.hippoTab.config.TabConfig;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.mwtw.hippoTab.Core.ERROR_TRACKER;

public final class BelowNameService {
    private static final String OBJECTIVE_NAME = "hippotab_health";

    private final JavaPlugin plugin;
    private final TabConfig config;
    private final TabTextFormatter formatter;
    private final PlaceholderService placeholderService;
    private final ConditionParser conditionParser;
    private Scoreboard scoreboard;
    private Objective objective;
    private BukkitTask updateTask;
    private final Map<UUID, Integer> cachedScores = new ConcurrentHashMap<>();
    private final Map<UUID, String> cachedFancyValues = new ConcurrentHashMap<>();

    public BelowNameService(JavaPlugin plugin, TabConfig config, TabTextFormatter formatter, 
                            PlaceholderService placeholderService, ConditionParser conditionParser) {
        this.plugin = plugin;
        this.config = config;
        this.formatter = formatter;
        this.placeholderService = placeholderService;
        this.conditionParser = conditionParser;
    }

    private Scoreboard getScoreboard() {
        if (scoreboard == null) {
            scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        }
        return scoreboard;
    }

    private void ensureObjectiveCreated() {
        if (objective != null) {
            return;
        }

        try {
            Scoreboard sb = getScoreboard();
            objective = sb.getObjective(OBJECTIVE_NAME);
            if (objective != null) {
                objective.unregister();
            }

            // Display name is the label only (e.g., "❤")
            // The score value will be shown automatically by Minecraft
            objective = sb.registerNewObjective(
                OBJECTIVE_NAME,
                Criteria.DUMMY,
                formatter.toComponent(null, config.belownameTitle())
            );
            objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create below-name objective: " + e.getMessage());
            ERROR_TRACKER.trackError(e);
        }
    }

    public void start() {
        if (!config.belownameEnabled()) {
            return;
        }
        
        ensureObjectiveCreated();
        updateAll();
        updateTask = Bukkit.getScheduler().runTaskTimer(
            plugin,
            this::updateAll,
            config.belownameUpdateIntervalTicks(),
            config.belownameUpdateIntervalTicks()
        );
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        if (objective != null) {
            objective.unregister();
            objective = null;
        }
        cachedScores.clear();
        cachedFancyValues.clear();
    }

    public void updateAll() {
        if (!config.belownameEnabled()) {
            return;
        }

        ensureObjectiveCreated();

        if (objective == null) {
            return;
        }

        boolean hasVisibleEntry = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (updatePlayerInternal(player)) {
                hasVisibleEntry = true;
            }
        }
        setBelowNameVisible(hasVisibleEntry);
    }

    public void updatePlayer(Player player) {
        if (!config.belownameEnabled()) {
            return;
        }

        ensureObjectiveCreated();

        if (objective == null) {
            return;
        }

        updatePlayerInternal(player);
        refreshDisplaySlot();
    }

    private boolean updatePlayerInternal(Player player) {
        String entry = player.getName();
        UUID uuid = player.getUniqueId();

        if (config.belownameDisableCondition() != null
            && !config.belownameDisableCondition().isBlank()
            && conditionParser.evaluate(player, config.belownameDisableCondition())) {
            if (cachedScores.remove(uuid) != null || cachedFancyValues.remove(uuid) != null) {
                clearScore(entry);
            }
            return false;
        }

        // Get value from placeholder
        String valueStr = placeholderService.apply(player, config.belownameValue());
        if (valueStr == null || valueStr.isBlank()) {
            if (cachedScores.remove(uuid) != null || cachedFancyValues.remove(uuid) != null) {
                clearScore(entry);
            }
            return false;
        }

        // Parse as number
        try {
            // Remove any non-numeric characters except decimal point and minus
            String cleaned = valueStr.replaceAll("[^0-9.\\-]", "");
            if (cleaned.isEmpty()) {
                if (cachedScores.remove(uuid) != null || cachedFancyValues.remove(uuid) != null) {
                    clearScore(entry);
                }
                return false;
            }
            int value = (int) Math.round(Double.parseDouble(cleaned));
            Score score = objective.getScore(entry);

            String fancyRendered = resolveFancyValue(player);
            Integer prevScore = cachedScores.get(uuid);
            String prevFancy = cachedFancyValues.get(uuid);

            boolean scoreChanged = prevScore == null || prevScore != value;
            boolean fancyChanged = !java.util.Objects.equals(prevFancy, fancyRendered);

            if (scoreChanged) {
                score.setScore(value);
                cachedScores.put(uuid, value);
            }
            if (fancyChanged) {
                applyFancyNumberFormatRendered(score, fancyRendered, player);
                cachedFancyValues.put(uuid, fancyRendered);
            }
            return true;
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("Could not parse below-name value for " + player.getName() + ": " + valueStr);
            cachedScores.remove(uuid);
            cachedFancyValues.remove(uuid);
            clearScore(entry);
            return false;
        }
    }

    public void removePlayer(Player player) {
        cachedScores.remove(player.getUniqueId());
        cachedFancyValues.remove(player.getUniqueId());
        if (objective != null) {
            clearScore(player.getName());
            refreshDisplaySlot();
        }
    }

    private void clearScore(String entry) {
        objective.getScore(entry).resetScore();
    }

    private void refreshDisplaySlot() {
        boolean hasVisibleEntry = false;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (isVisibleForBelowName(online)) {
                hasVisibleEntry = true;
                break;
            }
        }
        setBelowNameVisible(hasVisibleEntry);
    }

    private boolean isVisibleForBelowName(Player player) {
        if (config.belownameDisableCondition() != null
            && !config.belownameDisableCondition().isBlank()
            && conditionParser.evaluate(player, config.belownameDisableCondition())) {
            return false;
        }

        String valueStr = placeholderService.apply(player, config.belownameValue());
        if (valueStr == null || valueStr.isBlank()) {
            return false;
        }

        String cleaned = valueStr.replaceAll("[^0-9.\\-]", "");
        return !cleaned.isEmpty();
    }

    private void setBelowNameVisible(boolean visible) {
        DisplaySlot expected = visible ? DisplaySlot.BELOW_NAME : null;
        if (objective.getDisplaySlot() != expected) {
            objective.setDisplaySlot(expected);
        }
    }

    private String resolveFancyValue(Player player) {
        String fancyValue = config.belownameFancyValue();
        if (fancyValue == null || fancyValue.isBlank()) {
            return null;
        }
        String rendered = placeholderService.apply(player, fancyValue);
        if (rendered == null || rendered.isBlank()) {
            rendered = config.belownameFancyValueDefault();
        }
        return (rendered == null || rendered.isBlank()) ? null : rendered;
    }

    private void applyFancyNumberFormatRendered(Score score, String rendered, Player player) {
        if (rendered == null) {
            score.numberFormat(null);
        } else {
            score.numberFormat(NumberFormat.fixed(formatter.toComponent(player, rendered)));
        }
    }
}
