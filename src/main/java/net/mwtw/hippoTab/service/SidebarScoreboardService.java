package net.mwtw.hippoTab.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.score.BlankScoreFormat;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mwtw.hippoTab.config.TabConfig;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SidebarScoreboardService {
    private static final String OBJECTIVE_NAME = "ht_sidebar";
    private static final String TEAM_PREFIX = "htsb_";
    // Sidebar display slot in the Minecraft protocol
    private static final int SIDEBAR_SLOT = 1;
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
    // last rendered state per player for change detection
    private final Map<UUID, SidebarRenderState> renderedStates = new ConcurrentHashMap<>();

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

    // No-op: NameTagService now uses PacketEvents directly, main-board team sync is gone.
    public void markTeamsDirty() {}

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            removePlayer(player);
        }
        renderedStates.clear();
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

        SidebarRenderState nextState = buildState(player);
        SidebarRenderState prevState = renderedStates.put(player.getUniqueId(), nextState);

        if (prevState == null) {
            initializePlayer(player, nextState);
            return;
        }

        if (prevState.equals(nextState)) {
            return;
        }

        // Title changed
        if (!Objects.equals(prevState.titleText(), nextState.titleText())) {
            sendPacket(player, new WrapperPlayServerScoreboardObjective(
                OBJECTIVE_NAME,
                WrapperPlayServerScoreboardObjective.ObjectiveMode.UPDATE,
                formatter.fromMiniMessage(nextState.titleText()),
                WrapperPlayServerScoreboardObjective.RenderType.INTEGER
            ));
        }

        int prevCount = prevState.lineTexts().size();
        int nextCount = nextState.lineTexts().size();

        // Update changed lines
        for (int i = 0; i < Math.min(prevCount, nextCount); i++) {
            String nextText = nextState.lineTexts().get(i);
            if (!Objects.equals(prevState.lineTexts().get(i), nextText)) {
                sendLineTeamUpdate(player, i, nextText);
            }
        }

        // Add new lines
        for (int i = prevCount; i < nextCount; i++) {
            sendLineTeamCreate(player, i, nextState.lineTexts().get(i));
        }

        // Remove excess lines
        for (int i = nextCount; i < prevCount; i++) {
            sendLineRemove(player, i);
        }

        // Re-score all lines if count changed (scores shift)
        if (prevCount != nextCount) {
            for (int i = 0; i < nextCount; i++) {
                sendLineScore(player, i, nextCount);
            }
        }
    }

    public void removePlayer(Player player) {
        SidebarRenderState state = renderedStates.remove(player.getUniqueId());
        if (state == null || !player.isOnline()) {
            return;
        }
        sendPacket(player, new WrapperPlayServerScoreboardObjective(
            OBJECTIVE_NAME,
            WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
            Component.empty(),
            WrapperPlayServerScoreboardObjective.RenderType.INTEGER
        ));
        for (int i = 0; i < state.lineTexts().size(); i++) {
            sendPacket(player, new WrapperPlayServerTeams(
                TEAM_PREFIX + i,
                WrapperPlayServerTeams.TeamMode.REMOVE,
                Optional.empty(),
                Collections.emptyList()
            ));
        }
    }

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    private void initializePlayer(Player player, SidebarRenderState state) {
        // 1. Create objective
        sendPacket(player, new WrapperPlayServerScoreboardObjective(
            OBJECTIVE_NAME,
            WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE,
            formatter.fromMiniMessage(state.titleText()),
            WrapperPlayServerScoreboardObjective.RenderType.INTEGER
        ));

        // 2. Create line teams + set scores
        int lineCount = state.lineTexts().size();
        for (int i = 0; i < lineCount; i++) {
            sendLineTeamCreate(player, i, state.lineTexts().get(i));
            sendLineScore(player, i, lineCount);
        }

        // 3. Show in sidebar slot
        sendPacket(player, new WrapperPlayServerDisplayScoreboard(SIDEBAR_SLOT, OBJECTIVE_NAME));
    }

    // -------------------------------------------------------------------------
    // Per-line helpers
    // -------------------------------------------------------------------------

    private void sendLineTeamCreate(Player player, int index, String lineText) {
        WrapperPlayServerTeams.ScoreBoardTeamInfo info = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
            Component.empty(),
            formatter.fromMiniMessage(lineText),
            Component.empty(),
            WrapperPlayServerTeams.NameTagVisibility.NEVER,
            WrapperPlayServerTeams.CollisionRule.NEVER,
            NamedTextColor.WHITE,
            WrapperPlayServerTeams.OptionData.NONE
        );
        sendPacket(player, new WrapperPlayServerTeams(
            TEAM_PREFIX + index,
            WrapperPlayServerTeams.TeamMode.CREATE,
            Optional.of(info),
            List.of(LINE_ENTRIES[index])
        ));
    }

    private void sendLineTeamUpdate(Player player, int index, String lineText) {
        WrapperPlayServerTeams.ScoreBoardTeamInfo info = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
            Component.empty(),
            formatter.fromMiniMessage(lineText),
            Component.empty(),
            WrapperPlayServerTeams.NameTagVisibility.NEVER,
            WrapperPlayServerTeams.CollisionRule.NEVER,
            NamedTextColor.WHITE,
            WrapperPlayServerTeams.OptionData.NONE
        );
        sendPacket(player, new WrapperPlayServerTeams(
            TEAM_PREFIX + index,
            WrapperPlayServerTeams.TeamMode.UPDATE,
            Optional.of(info),
            Collections.emptyList()
        ));
    }

    private void sendLineScore(Player player, int index, int totalLines) {
        int score = totalLines - index;
        ScoreFormat format = config.scoreboardHideNumber() ? BlankScoreFormat.INSTANCE : null;
        sendPacket(player, new WrapperPlayServerUpdateScore(
            LINE_ENTRIES[index],
            WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
            OBJECTIVE_NAME,
            score,
            null,
            format
        ));
    }

    private void sendLineRemove(Player player, int index) {
        sendPacket(player, new WrapperPlayServerResetScore(LINE_ENTRIES[index], OBJECTIVE_NAME));
        sendPacket(player, new WrapperPlayServerTeams(
            TEAM_PREFIX + index,
            WrapperPlayServerTeams.TeamMode.REMOVE,
            Optional.empty(),
            Collections.emptyList()
        ));
    }

    // -------------------------------------------------------------------------
    // Packet helpers
    // -------------------------------------------------------------------------

    private void sendPacket(Player player, WrapperPlayServerScoreboardObjective packet) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, packet);
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Skipped sidebar objective packet for " + player.getName() + ": " + e.getClass().getSimpleName());
        }
    }

    private void sendPacket(Player player, WrapperPlayServerTeams packet) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, packet);
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Skipped sidebar team packet for " + player.getName() + ": " + e.getClass().getSimpleName());
        }
    }

    private void sendPacket(Player player, WrapperPlayServerUpdateScore packet) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, packet);
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Skipped sidebar score packet for " + player.getName() + ": " + e.getClass().getSimpleName());
        }
    }

    private void sendPacket(Player player, WrapperPlayServerResetScore packet) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, packet);
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Skipped sidebar reset packet for " + player.getName() + ": " + e.getClass().getSimpleName());
        }
    }

    private void sendPacket(Player player, WrapperPlayServerDisplayScoreboard packet) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, packet);
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Skipped sidebar display packet for " + player.getName() + ": " + e.getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------------------------
    // State helpers
    // -------------------------------------------------------------------------

    private SidebarRenderState buildState(Player player) {
        return new SidebarRenderState(
            formatter.toMiniMessageText(player, config.scoreboardTitle()),
            formatter.toMiniMessageLines(player, sanitizeLines(config.scoreboardLines()))
        );
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

    private record SidebarRenderState(String titleText, List<String> lineTexts) {}
}
