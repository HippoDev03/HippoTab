package net.mwtw.hippoTab.service;

import net.kyori.adventure.text.Component;
import net.mwtw.hippoTab.config.TabConfig;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class NametagService {
    private static final String DISPLAY_TAG = "hippotab_nametag_display";
    private static final String HIDE_TEAM_NAME = "ht_textdisplay_hide";

    private final JavaPlugin plugin;
    private final TabConfig config;
    private final TabTextFormatter formatter;
    private final PlaceholderService placeholderService;
    private final ConditionParser conditionParser;
    private final Map<UUID, UUID> displayByPlayer = new HashMap<>();
    private BukkitTask updateTask;
    private Scoreboard scoreboard;
    private Team hideNametagTeam;

    public NametagService(JavaPlugin plugin, TabConfig config, TabTextFormatter formatter,
                          PlaceholderService placeholderService, ConditionParser conditionParser) {
        this.plugin = plugin;
        this.config = config;
        this.formatter = formatter;
        this.placeholderService = placeholderService;
        this.conditionParser = conditionParser;
    }

    public void start() {
        if (!shouldRun()) {
            return;
        }

        updateAll();
        updateTask = Bukkit.getScheduler().runTaskTimer(
            plugin,
            this::updateAll,
            config.nametagUpdateIntervalTicks(),
            config.nametagUpdateIntervalTicks()
        );
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        restoreVanillaNametags();
        removeAllDisplays();
    }

    public void updateAll() {
        if (!shouldRun()) {
            removeAllDisplays();
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayer(player);
        }
        pruneStaleMappings();
    }

    public void updatePlayer(Player player) {
        if (!shouldRun()) {
            setVanillaNametagHidden(player, false);
            removePlayer(player);
            return;
        }

        if (isDisplayDisabled(player)) {
            setVanillaNametagHidden(player, false);
            removePlayer(player);
            return;
        }

        setVanillaNametagHidden(player, true);

        Component text = buildDisplayText(player);
        if (text == null) {
            removePlayer(player);
            return;
        }

        TextDisplay display = getOrCreateDisplay(player);
        if (display == null) {
            return;
        }

        display.text(text);
        syncDisplayAttachment(player, display);
    }

    public void removePlayer(Player player) {
        if (hideNametagTeam != null) {
            hideNametagTeam.removeEntity(player);
        }
        UUID entityId = displayByPlayer.remove(player.getUniqueId());
        if (entityId != null) {
            removeEntity(entityId);
        }
        for (Entity passenger : player.getPassengers()) {
            if (passenger instanceof TextDisplay && passenger.getScoreboardTags().contains(DISPLAY_TAG)) {
                passenger.remove();
            }
        }
    }

    public String getFormattedName(Player player) {
        return getFormattedPrefix(player) + player.getName() + getFormattedSuffix(player);
    }

    public String getFormattedPrefix(Player player) {
        return placeholderService.apply(player, config.nametagPrefix() == null ? "" : config.nametagPrefix());
    }

    public String getFormattedSuffix(Player player) {
        return placeholderService.apply(player, config.nametagSuffix() == null ? "" : config.nametagSuffix());
    }

    private Integer resolveValue(Player player) {
        String valueStr = placeholderService.apply(player, config.belownameValue());
        try {
            String cleaned = valueStr.replaceAll("[^0-9.\\-]", "");
            if (cleaned.isEmpty()) {
                return null;
            }
            return (int) Math.round(Double.parseDouble(cleaned));
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("Could not parse nametag second-line value for " + player.getName() + ": " + valueStr);
            return null;
        }
    }

    private Component buildDisplayText(Player player) {
        Component nameLine = buildNametagLine(player);
        Component valueLine = buildBelowValueLine(player);

        if (nameLine == null && valueLine == null) {
            return null;
        }
        if (nameLine == null) {
            return valueLine;
        }
        if (valueLine == null) {
            return nameLine;
        }
        return nameLine.append(Component.newline()).append(valueLine);
    }

    private Component buildNametagLine(Player player) {
        if (!config.nametagEnabled()) {
            return null;
        }

        String prefix = config.nametagPrefix() == null ? "" : config.nametagPrefix();
        String suffix = config.nametagSuffix() == null ? "" : config.nametagSuffix();
        return formatter.toComponent(player, prefix + player.getName() + suffix);
    }

    private Component buildBelowValueLine(Player player) {
        if (!config.belownameEnabled()) {
            return null;
        }
        if (config.belownameDisableIf() != null
            && !config.belownameDisableIf().isBlank()
            && conditionParser.evaluate(player, config.belownameDisableIf())) {
            return null;
        }

        Integer value = resolveValue(player);
        if (value == null || value == 0) {
            return null;
        }

        String format = config.belownameFormat() == null ? "" : config.belownameFormat();
        if (format.isBlank()) {
            return Component.text(value);
        }
        return formatter.toComponent(player, format)
            .append(Component.space())
            .append(Component.text(value));
    }

    private TextDisplay getOrCreateDisplay(Player player) {
        UUID playerId = player.getUniqueId();
        UUID existingId = displayByPlayer.get(playerId);
        if (existingId != null) {
            Entity entity = Bukkit.getEntity(existingId);
            if (entity instanceof TextDisplay display && display.isValid()) {
                return display;
            }
            displayByPlayer.remove(playerId);
        }

        TextDisplay display = player.getWorld().spawn(player.getLocation(), TextDisplay.class, spawned -> {
            spawned.addScoreboardTag(DISPLAY_TAG);
            spawned.setPersistent(false);
            spawned.setInvulnerable(true);
            spawned.setGravity(false);
            spawned.setBillboard(resolveBillboard());
            spawned.setAlignment(TextDisplay.TextAlignment.CENTER);
            spawned.setSeeThrough(true);
            spawned.setShadowed(false);
            spawned.setDefaultBackground(false);
            spawned.setVisibleByDefault(true);
            spawned.setTransformation(buildHeadOffsetTransform());
            spawned.setViewRange(128.0F);
        });

        displayByPlayer.put(playerId, display.getUniqueId());
        return display;
    }

    private void syncDisplayAttachment(Player player, TextDisplay display) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            if (display.getVehicle() != null) {
                display.leaveVehicle();
            }
            Location headLocation = player.getLocation().add(0.0, player.getHeight() + config.nametagOffsetY(), 0.0);
            display.teleport(headLocation);
            return;
        }
        display.setTransformation(buildHeadOffsetTransform());
        ensureMounted(player, display);
    }

    private void ensureMounted(Player player, TextDisplay display) {
        if (display.getVehicle() == player) {
            return;
        }
        if (!player.addPassenger(display)) {
            display.remove();
            displayByPlayer.remove(player.getUniqueId());
        }
    }

    private void removeAllDisplays() {
        for (UUID entityId : displayByPlayer.values()) {
            removeEntity(entityId);
        }
        displayByPlayer.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            for (Entity passenger : player.getPassengers()) {
                if (passenger instanceof TextDisplay && passenger.getScoreboardTags().contains(DISPLAY_TAG)) {
                    passenger.remove();
                }
            }
        }
    }

    private void pruneStaleMappings() {
        Iterator<Map.Entry<UUID, UUID>> iterator = displayByPlayer.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, UUID> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            Entity entity = Bukkit.getEntity(entry.getValue());
            if (player == null || !player.isOnline() || !(entity instanceof TextDisplay) || !entity.isValid()) {
                if (entity != null) {
                    entity.remove();
                }
                iterator.remove();
            }
        }
    }

    private void removeEntity(UUID entityId) {
        Entity entity = Bukkit.getEntity(entityId);
        if (entity != null) {
            entity.remove();
        }
    }

    private boolean shouldRun() {
        return config.nametagEnabled() || config.belownameEnabled();
    }

    private boolean isDisplayDisabled(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        return config.nametagDisableIf() != null
            && !config.nametagDisableIf().isBlank()
            && conditionParser.evaluate(player, config.nametagDisableIf());
    }

    private Display.Billboard resolveBillboard() {
        String raw = config.nametagBillboard();
        if (raw == null || raw.isBlank()) {
            return Display.Billboard.VERTICAL;
        }
        try {
            return Display.Billboard.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return Display.Billboard.VERTICAL;
        }
    }

    private Transformation buildHeadOffsetTransform() {
        return new Transformation(
            new Vector3f(0.0F, (float) config.nametagOffsetY(), 0.0F),
            new Quaternionf(),
            new Vector3f(1.0F, 1.0F, 1.0F),
            new Quaternionf()
        );
    }

    private Scoreboard getScoreboard() {
        if (scoreboard == null) {
            scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        }
        return scoreboard;
    }

    private Team getOrCreateHideTeam() {
        if (hideNametagTeam != null) {
            return hideNametagTeam;
        }
        Scoreboard sb = getScoreboard();
        hideNametagTeam = sb.getTeam(HIDE_TEAM_NAME);
        if (hideNametagTeam == null) {
            hideNametagTeam = sb.registerNewTeam(HIDE_TEAM_NAME);
        }
        hideNametagTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        return hideNametagTeam;
    }

    private void setVanillaNametagHidden(Player player, boolean hidden) {
        if (!config.nametagHideVanillaNametag()) {
            return;
        }
        Team team = getOrCreateHideTeam();
        if (hidden) {
            team.addEntity(player);
            return;
        }
        team.removeEntity(player);
    }

    private void restoreVanillaNametags() {
        if (hideNametagTeam == null) {
            return;
        }
        hideNametagTeam.unregister();
        hideNametagTeam = null;
    }
}
