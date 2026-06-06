package net.mwtw.hippoTab.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.score.FixedScoreFormat;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import net.kyori.adventure.text.Component;
import net.mwtw.hippoTab.config.TabConfig;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BelowNameService {
    private static final String OBJECTIVE_NAME = "hippotab_health";
    private static final int BELOW_NAME_SLOT = 2;

    private final JavaPlugin plugin;
    private final TabConfig config;
    private final TabTextFormatter formatter;
    private final PlaceholderService placeholderService;
    private final ConditionParser conditionParser;
    private BukkitTask updateTask;
    private PacketListenerAbstract displayInterceptor;

    private final Map<UUID, Integer> cachedScores = new ConcurrentHashMap<>();
    private final Map<UUID, String> cachedFancyValues = new ConcurrentHashMap<>();
    // targets whose score is currently disabled — ResetScore already sent, avoid re-sending every tick
    private final java.util.Set<UUID> disabledScorePlayers = ConcurrentHashMap.newKeySet();

    public BelowNameService(JavaPlugin plugin, TabConfig config, TabTextFormatter formatter,
                            PlaceholderService placeholderService, ConditionParser conditionParser) {
        this.plugin = plugin;
        this.config = config;
        this.formatter = formatter;
        this.placeholderService = placeholderService;
        this.conditionParser = conditionParser;
    }

    public void start() {
        if (!config.belownameEnabled()) {
            return;
        }
        // Block any DisplayScoreboard(slot=2) not from us (vanilla health override etc.)
        // Allow empty string through so we can send our own hide packets.
        displayInterceptor = new PacketListenerAbstract() {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() != PacketType.Play.Server.DISPLAY_SCOREBOARD) {
                    return;
                }
                WrapperPlayServerDisplayScoreboard packet = new WrapperPlayServerDisplayScoreboard(event);
                String name = packet.getScoreName();
                if (packet.getPosition() == BELOW_NAME_SLOT
                        && !OBJECTIVE_NAME.equals(name)
                        && !name.isEmpty()) {
                    event.setCancelled(true);
                }
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(displayInterceptor);

        for (Player p : Bukkit.getOnlinePlayers()) {
            sendObjectiveRemoveTo(p);
            sendObjectiveTo(p);
            sendAllScoresTo(p);
            sendDisplayTo(p);
        }
        updateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            this::updateAll,
            config.belownameUpdateIntervalTicks(),
            config.belownameUpdateIntervalTicks()
        );
    }

    public void stop() {
        if (displayInterceptor != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(displayInterceptor);
            displayInterceptor = null;
        }
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            sendObjectiveRemoveTo(p);
        }
        cachedScores.clear();
        cachedFancyValues.clear();
        disabledScorePlayers.clear();
    }

    public void onPlayerJoin(Player joiner) {
        if (!config.belownameEnabled()) {
            return;
        }
        sendObjectiveRemoveTo(joiner);
        sendObjectiveTo(joiner);
        sendAllScoresTo(joiner);
        // Joiner missed prior N/A packets for already-disabled players — send them now
        for (UUID disabledUuid : disabledScorePlayers) {
            Player disabled = Bukkit.getPlayer(disabledUuid);
            if (disabled != null) {
                sendScore(joiner, disabled.getName(), 0, new FixedScoreFormat(Component.text("N/A")));
            }
        }
        sendDisplayTo(joiner);
        broadcastScore(joiner);
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!joiner.isOnline()) return;
            broadcastScore(joiner);
        }, 2L);
    }

    public void onPlayerChangeWorld(Player player) {
        if (!config.belownameEnabled()) {
            return;
        }
        // Clear all caches so broadcastScore unconditionally re-evaluates and re-sends
        disabledScorePlayers.remove(player.getUniqueId());
        cachedScores.remove(player.getUniqueId());
        cachedFancyValues.remove(player.getUniqueId());
        broadcastScore(player);
    }

    public void removePlayer(Player player) {
        cachedScores.remove(player.getUniqueId());
        cachedFancyValues.remove(player.getUniqueId());
        disabledScorePlayers.remove(player.getUniqueId());
        String name = player.getName();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(player)) {
                sendResetScore(viewer, name);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Periodic update
    // -------------------------------------------------------------------------

    private void updateAll() {
        List<Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        long interval = config.belownameUpdateIntervalTicks();
        long spread = Math.min(players.size(), Math.min(5, Math.max(1, interval / 2)));

        for (int i = 0; i < players.size(); i++) {
            final Player p = players.get(i);
            long delay = i % spread;
            if (delay == 0) {
                broadcastScore(p);
            } else {
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                    if (p.isOnline()) broadcastScore(p);
                }, delay);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Core score logic
    // -------------------------------------------------------------------------

    private void broadcastScore(Player target) {
        ScoreData data = resolveScore(target);
        UUID uuid = target.getUniqueId();
        String name = target.getName();

        if (data == null) {
            cachedScores.remove(uuid);
            cachedFancyValues.remove(uuid);
            if (disabledScorePlayers.add(uuid)) {
                ScoreFormat naFormat = new FixedScoreFormat(Component.text(" N/A"));
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    sendScore(viewer, name, 0, naFormat);
                }
            }
            return;
        }

        disabledScorePlayers.remove(uuid);

        Integer prevScore = cachedScores.get(uuid);
        String prevFancy = cachedFancyValues.get(uuid);
        boolean scoreChanged = !Objects.equals(prevScore, data.score());
        boolean fancyChanged = !Objects.equals(prevFancy, data.fancyRendered());

        if (!scoreChanged && !fancyChanged) {
            return;
        }

        cachedScores.put(uuid, data.score());
        cachedFancyValues.put(uuid, data.fancyRendered());

        ScoreFormat format = data.fancyRendered() != null
            ? new FixedScoreFormat(formatter.toComponent(target, data.fancyRendered()))
            : null;

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            sendScore(viewer, name, data.score(), format);
        }
    }

    private record ScoreData(int score, String fancyRendered) {}

    private ScoreData resolveScore(Player player) {
        if (config.belownameDisableCondition() != null
            && !config.belownameDisableCondition().isBlank()
            && conditionParser.evaluate(player, config.belownameDisableCondition())) {
            return null;
        }

        String valueStr = placeholderService.apply(player, config.belownameValue());
        if (valueStr == null || valueStr.isBlank()) {
            return null;
        }

        String cleaned = valueStr.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty()) {
            return null;
        }

        try {
            int value = (int) Math.round(Double.parseDouble(cleaned));
            return new ScoreData(value, resolveFancyValue(player));
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("Could not parse below-name value for " + player.getName() + ": " + valueStr);
            return null;
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

    // -------------------------------------------------------------------------
    // Packet helpers
    // -------------------------------------------------------------------------

    private void sendObjectiveTo(Player viewer) {
        Component title = formatter.toComponent(null, config.belownameTitle());
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
            new WrapperPlayServerScoreboardObjective(
                OBJECTIVE_NAME,
                WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE,
                title,
                WrapperPlayServerScoreboardObjective.RenderType.INTEGER
            ));
    }

    private void sendObjectiveRemoveTo(Player viewer) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
            new WrapperPlayServerScoreboardObjective(
                OBJECTIVE_NAME,
                WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
                Component.empty(),
                WrapperPlayServerScoreboardObjective.RenderType.INTEGER
            ));
    }

    private void sendAllScoresTo(Player viewer) {
        for (Player target : Bukkit.getOnlinePlayers()) {
            Integer score = cachedScores.get(target.getUniqueId());
            if (score == null) {
                continue;
            }
            String fancy = cachedFancyValues.get(target.getUniqueId());
            ScoreFormat format = fancy != null
                ? new FixedScoreFormat(formatter.toComponent(target, fancy))
                : null;
            sendScore(viewer, target.getName(), score, format);
        }
    }

    private void sendDisplayTo(Player viewer) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
            new WrapperPlayServerDisplayScoreboard(BELOW_NAME_SLOT, OBJECTIVE_NAME));
    }

    private void sendScore(Player viewer, String entityName, int score, ScoreFormat format) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
            new WrapperPlayServerUpdateScore(
                entityName,
                WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
                OBJECTIVE_NAME,
                score,
                null,
                format
            ));
    }

    private void sendResetScore(Player viewer, String entityName) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
            new WrapperPlayServerResetScore(entityName, OBJECTIVE_NAME));
    }
}
