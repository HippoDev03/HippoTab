package net.mwtw.hippoTab.service;

import net.mwtw.hippoTab.config.TabConfig;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

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
                formatter.toComponent(null, config.belownameFormat())
            );
            objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create below-name objective: " + e.getMessage());
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
    }

    public void updateAll() {
        if (!config.belownameEnabled()) {
            return;
        }

        ensureObjectiveCreated();

        if (objective == null) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayer(player);
        }
    }

    public void updatePlayer(Player player) {
        if (!config.belownameEnabled()) {
            return;
        }

        ensureObjectiveCreated();

        if (objective == null) {
            return;
        }

        if (!conditionParser.evaluate(player, config.belownameDisableIf())) {
            objective.getScore(player).resetScore();
            return;
        }

        // Get value from placeholder
        String valueStr = placeholderService.apply(player, config.belownameValue());
        
        // Parse as number
        int value = 0;
        try {
            // Remove any non-numeric characters except decimal point and minus
            String cleaned = valueStr.replaceAll("[^0-9.\\-]", "");
            if (!cleaned.isEmpty()) {
                value = (int) Math.round(Double.parseDouble(cleaned));
            }
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("Could not parse below-name value for " + player.getName() + ": " + valueStr);
        }

        objective.getScore(player).setScore(value);
    }

    public void removePlayer(Player player) {
        if (objective != null) {
            objective.getScore(player).resetScore();
        }
    }
}
