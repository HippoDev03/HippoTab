package net.mwtw.hippoTab.service;

import net.kyori.adventure.text.format.NamedTextColor;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.mwtw.hippoTab.config.TabConfig;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SidebarScoreboardService {
    private static final String OBJECTIVE_NAME = "ht_sidebar";
    private static final String TEAM_PREFIX = "htsb_";
    private static final int MAX_LINES = 15;
    private static final String[] LINE_ENTRIES = {
        ChatColor.BLACK.toString(),
        ChatColor.DARK_BLUE.toString(),
        ChatColor.DARK_GREEN.toString(),
        ChatColor.DARK_AQUA.toString(),
        ChatColor.DARK_RED.toString(),
        ChatColor.DARK_PURPLE.toString(),
        ChatColor.GOLD.toString(),
        ChatColor.GRAY.toString(),
        ChatColor.DARK_GRAY.toString(),
        ChatColor.BLUE.toString(),
        ChatColor.GREEN.toString(),
        ChatColor.AQUA.toString(),
        ChatColor.RED.toString(),
        ChatColor.LIGHT_PURPLE.toString(),
        ChatColor.YELLOW.toString()
    };

    private final JavaPlugin plugin;
    private final TabConfig config;
    private final TabTextFormatter formatter;
    private BukkitTask updateTask;
    private final Map<UUID, Scoreboard> personalBoards = new ConcurrentHashMap<>();
    private final Map<UUID, Scoreboard> previousBoards = new ConcurrentHashMap<>();
    private final Map<UUID, SidebarRenderState> renderedStates = new ConcurrentHashMap<>();
    private int mainBoardTeamsVersion = 0;
    private final Map<UUID, Integer> boardTeamsSyncedVersion = new ConcurrentHashMap<>();
    private final Map<String, Integer> cachedBelowNameScores = new ConcurrentHashMap<>();

    public SidebarScoreboardService(JavaPlugin plugin, TabConfig config, TabTextFormatter formatter) {
        this.plugin = plugin;
        this.config = config;
        this.formatter = formatter;
    }

    public void start() {
        if (!config.scoreboardEnabled()) {
            return;
        }

        updateAll();
        updateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            this::updateAll,
            config.scoreboardUpdateIntervalTicks(),
            config.scoreboardUpdateIntervalTicks()
        );
    }

    public void markTeamsDirty() {
        mainBoardTeamsVersion++;
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            removePlayer(player);
        }
        personalBoards.clear();
        previousBoards.clear();
        renderedStates.clear();
        boardTeamsSyncedVersion.clear();
        cachedBelowNameScores.clear();
    }

    public void updateAll() {
        if (!config.scoreboardEnabled()) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayer(player);
        }
    }

    public void updatePlayer(Player player) {
        if (!config.scoreboardEnabled()) {
            return;
        }

        Scoreboard board = getOrCreateBoard(player);
        syncMainBoardTeamsAndBelowName(player.getUniqueId(), board);
        SidebarRenderState nextState = buildState(player);
        SidebarRenderState previousState = renderedStates.put(player.getUniqueId(), nextState);
        Objective objective = getOrCreateObjective(board, nextState.titleText());
        if (previousState != null && previousState.equals(nextState)) {
            return;
        }

        int score = nextState.lineTexts().size();
        for (int index = 0; index < nextState.lineTexts().size(); index++) {
            String teamName = TEAM_PREFIX + index;
            String entry = LINE_ENTRIES[index];

            Team team = board.getTeam(teamName);
            if (team == null) {
                team = board.registerNewTeam(teamName);
            }

            String lineText = nextState.lineTexts().get(index);
            if (previousState == null
                || index >= previousState.lineTexts().size()
                || !Objects.equals(previousState.lineTexts().get(index), lineText)) {
                team.prefix(formatter.fromMiniMessage(lineText));
            }
            if (!team.hasEntry(entry)) {
                team.addEntry(entry);
            }
            objective.getScore(entry).setScore(score--);
        }

        cleanupUnusedSidebarTeams(board, nextState.lineTexts().size());
    }

    public void removePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        renderedStates.remove(uuid);
        boardTeamsSyncedVersion.remove(uuid);
        Scoreboard board = personalBoards.remove(uuid);
        if (board != null) {
            removeSidebarElements(board);
        } else {
            removeSidebarElements(player.getScoreboard());
        }

        Scoreboard previousBoard = previousBoards.remove(uuid);
        if (previousBoard != null && player.isOnline()) {
            player.setScoreboard(previousBoard);
        }
    }

    private Scoreboard getOrCreateBoard(Player player) {
        Scoreboard existingBoard = personalBoards.get(player.getUniqueId());
        if (existingBoard != null) {
            return existingBoard;
        }

        Scoreboard currentBoard = player.getScoreboard();
        Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        if (currentBoard == mainBoard) {
            Scoreboard personalBoard = Bukkit.getScoreboardManager().getNewScoreboard();
            previousBoards.put(player.getUniqueId(), currentBoard);
            personalBoards.put(player.getUniqueId(), personalBoard);
            player.setScoreboard(personalBoard);
            return personalBoard;
        }

        personalBoards.put(player.getUniqueId(), currentBoard);
        return currentBoard;
    }

    private void removeSidebarElements(Scoreboard board) {
        Objective objective = board.getObjective(OBJECTIVE_NAME);
        if (objective != null) {
            objective.unregister();
        }

        for (int i = 0; i < MAX_LINES; i++) {
            Team team = board.getTeam(TEAM_PREFIX + i);
            if (team != null) {
                team.unregister();
            }
        }
    }

    private Objective getOrCreateObjective(Scoreboard board, String titleText) {
        Objective objective = board.getObjective(OBJECTIVE_NAME);
        if (objective == null) {
            objective = board.registerNewObjective(
                OBJECTIVE_NAME,
                "dummy",
                formatter.fromMiniMessage(titleText)
            );
        } else {
            objective.displayName(formatter.fromMiniMessage(titleText));
        }

        if (config.scoreboardHideNumber()) {
            objective.numberFormat(NumberFormat.blank());
        } else {
            objective.numberFormat(null);
        }
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        return objective;
    }

    private List<String> sanitizeLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        int count = Math.min(MAX_LINES, lines.size());
        List<String> sanitized = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            sanitized.add(lines.get(i) == null ? "" : lines.get(i));
        }
        return sanitized;
    }

    private void cleanupUnusedSidebarTeams(Scoreboard board, int usedCount) {
        for (int i = usedCount; i < MAX_LINES; i++) {
            board.resetScores(LINE_ENTRIES[i]);
            Team team = board.getTeam(TEAM_PREFIX + i);
            if (team != null) {
                team.unregister();
            }
        }
    }

    private void syncMainBoardTeamsAndBelowName(UUID boardOwner, Scoreboard targetBoard) {
        Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        if (targetBoard == mainBoard) {
            return;
        }

        int syncedVersion = boardTeamsSyncedVersion.getOrDefault(boardOwner, -1);
        if (syncedVersion != mainBoardTeamsVersion) {
            for (Team sourceTeam : mainBoard.getTeams()) {
                Team targetTeam = targetBoard.getTeam(sourceTeam.getName());
                if (targetTeam == null) {
                    targetTeam = targetBoard.registerNewTeam(sourceTeam.getName());
                }

                targetTeam.displayName(sourceTeam.displayName());
                targetTeam.prefix(sourceTeam.prefix());
                targetTeam.suffix(sourceTeam.suffix());
                copyTeamColorSafely(sourceTeam, targetTeam);
                targetTeam.setAllowFriendlyFire(sourceTeam.allowFriendlyFire());
                targetTeam.setCanSeeFriendlyInvisibles(sourceTeam.canSeeFriendlyInvisibles());

                for (Team.Option option : Team.Option.values()) {
                    targetTeam.setOption(option, sourceTeam.getOption(option));
                }

                for (String entry : new ArrayList<>(targetTeam.getEntries())) {
                    if (!sourceTeam.hasEntry(entry)) {
                        targetTeam.removeEntry(entry);
                    }
                }
                for (String entry : sourceTeam.getEntries()) {
                    if (!targetTeam.hasEntry(entry)) {
                        targetTeam.addEntry(entry);
                    }
                }
            }
            boardTeamsSyncedVersion.put(boardOwner, mainBoardTeamsVersion);
        }

        Objective sourceBelow = mainBoard.getObjective(DisplaySlot.BELOW_NAME);
        if (sourceBelow == null) {
            return;
        }

        Objective targetBelow = targetBoard.getObjective(sourceBelow.getName());
        if (targetBelow != null && targetBelow.getDisplaySlot() != DisplaySlot.BELOW_NAME) {
            targetBelow.unregister();
            targetBelow = null;
        }
        if (targetBelow == null) {
            targetBelow = targetBoard.registerNewObjective(
                sourceBelow.getName(),
                sourceBelow.getTrackedCriteria(),
                sourceBelow.displayName(),
                sourceBelow.getRenderType()
            );
        } else {
            targetBelow.displayName(sourceBelow.displayName());
            targetBelow.setRenderType(sourceBelow.getRenderType());
        }
        targetBelow.numberFormat(sourceBelow.numberFormat());
        targetBelow.setDisplaySlot(DisplaySlot.BELOW_NAME);

        // Compute changed scores on main board since last sync
        Map<String, Integer> changedScores = new HashMap<>();
        for (String entry : mainBoard.getEntries()) {
            if (sourceBelow.getScore(entry).isScoreSet()) {
                int val = sourceBelow.getScore(entry).getScore();
                Integer cached = cachedBelowNameScores.get(entry);
                if (cached == null || cached != val) {
                    changedScores.put(entry, val);
                }
            }
        }

        // Update cached scores map
        for (Map.Entry<String, Integer> changed : changedScores.entrySet()) {
            cachedBelowNameScores.put(changed.getKey(), changed.getValue());
        }

        // Clear entries that no longer have a score on main board
        for (String entry : targetBoard.getEntries()) {
            if (!sourceBelow.getScore(entry).isScoreSet() && targetBelow.getScore(entry).isScoreSet()) {
                targetBelow.getScore(entry).resetScore();
                cachedBelowNameScores.remove(entry);
            }
        }

        // Apply only changed scores to this board
        for (Map.Entry<String, Integer> changed : changedScores.entrySet()) {
            targetBelow.getScore(changed.getKey()).setScore(changed.getValue());
            targetBelow.getScore(changed.getKey()).numberFormat(sourceBelow.getScore(changed.getKey()).numberFormat());
        }
    }

    private void copyTeamColorSafely(Team sourceTeam, Team targetTeam) {
        try {
            var sourceColor = sourceTeam.color();
            if (sourceColor == null) {
                return;
            }
            targetTeam.color(NamedTextColor.nearestTo(sourceColor));
        } catch (RuntimeException exception) {
            plugin.getLogger().fine(
                "Skipped syncing team color for team "
                    + sourceTeam.getName()
                    + " due to unsupported color state: "
                    + exception.getClass().getSimpleName()
            );
        }
    }

    private SidebarRenderState buildState(Player player) {
        return new SidebarRenderState(
            formatter.toMiniMessageText(player, config.scoreboardTitle()),
            formatter.toMiniMessageLines(player, sanitizeLines(config.scoreboardLines()))
        );
    }

    private record SidebarRenderState(String titleText, List<String> lineTexts) {
    }
}
