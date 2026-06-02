package net.mwtw.hippoTab.service;

import net.kyori.adventure.text.Component;
import net.mwtw.hippoTab.config.TabConfig;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TabService {
    private static final Pattern INTEGER_PATTERN = Pattern.compile("-?\\d+");
    private static final int ORDER_BASE = 1_000_000;
    private static final int ORDER_MIN = 0;
    private static final int ORDER_MAX = 2_000_000;

    private final JavaPlugin plugin;
    private final TabConfig config;
    private final TabTextFormatter formatter;
    private final PlaceholderService placeholderService;
    private NameTagService nameTagService;
    private BelowNameService belowNameService;
    private final Set<UUID> hiddenTabPlayers = new HashSet<>();
    private final Map<UUID, PlayerTabState> playerStates = new ConcurrentHashMap<>();

    private BukkitTask updateTask;

    public TabService(JavaPlugin plugin, TabConfig config, TabTextFormatter formatter, PlaceholderService placeholderService) {
        this.plugin = plugin;
        this.config = config;
        this.formatter = formatter;
        this.placeholderService = placeholderService;
    }

    public void setNameTagService(NameTagService nameTagService) {
        this.nameTagService = nameTagService;
    }

    public void setBelowNameService(BelowNameService belowNameService) {
        this.belowNameService = belowNameService;
    }

    public void start() {
        if (!config.tabEnabled()) {
            restoreAllTabEntries();
            return;
        }
        refreshAll();
        updateTask = Bukkit.getScheduler().runTaskTimer(
            plugin,
            this::refreshAll,
            config.updateIntervalTicks(),
            config.updateIntervalTicks()
        );
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
        }
        restoreAllTabEntries();
        playerStates.clear();
    }

    public void refreshAll() {
        if (!config.tabEnabled()) {
            restoreAllTabEntries();
            playerStates.clear();
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshPlayer(player);
        }
        syncTabVisibility();
    }

    public void refreshPlayer(Player player) {
        if (!config.tabEnabled()) {
            return;
        }
        PlayerTabState nextState = buildState(player);
        PlayerTabState previousState = playerStates.put(player.getUniqueId(), nextState);

        if (previousState == null
            || !Objects.equals(previousState.headerText(), nextState.headerText())
            || !Objects.equals(previousState.footerText(), nextState.footerText())) {
            Component header = formatter.fromMiniMessage(nextState.headerText());
            Component footer = formatter.fromMiniMessage(nextState.footerText());
            player.sendPlayerListHeaderAndFooter(header, footer);
        }

        if (nextState.playerListNameText() != null
            && (previousState == null || !Objects.equals(previousState.playerListNameText(), nextState.playerListNameText()))) {
            player.playerListName(formatter.fromMiniMessage(nextState.playerListNameText()));
        }

        if (nextState.listOrder() != null
            && (previousState == null || !Objects.equals(previousState.listOrder(), nextState.listOrder()))) {
            player.setPlayerListOrder(nextState.listOrder());
        }
    }

    public void refreshPlayerNextTick(Player player) {
        if (!config.tabEnabled()) {
            return;
        }

        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player onlinePlayer = Bukkit.getPlayer(playerId);
            if (onlinePlayer == null || !onlinePlayer.isOnline()) {
                return;
            }

            playerStates.remove(playerId);
            refreshPlayer(onlinePlayer);
            syncTabVisibility();
        }, 1L);
    }

    public void removePlayer(Player player) {
        if (player == null) {
            return;
        }

        playerStates.remove(player.getUniqueId());
        hiddenTabPlayers.remove(player.getUniqueId());
    }

    public void applySorting() {
        if (!config.tabEnabled() || !config.sortingEnabled()) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerTabState state = playerStates.computeIfAbsent(player.getUniqueId(), ignored -> buildState(player));
            if (state.listOrder() != null) {
                player.setPlayerListOrder(state.listOrder());
            }
        }
    }

    private int resolveRank(Player player) {
        String raw = placeholderService.apply(player, config.rankPlaceholder());
        Matcher matcher = INTEGER_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return config.defaultRank();
        }
        try {
            return Integer.parseInt(matcher.group());
        } catch (NumberFormatException ignored) {
            return config.defaultRank();
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void syncTabVisibility() {
        Set<UUID> hiddenNow = new HashSet<>();
        for (Player target : Bukkit.getOnlinePlayers()) {
            PlayerTabState state = playerStates.computeIfAbsent(target.getUniqueId(), ignored -> buildState(target));
            if (state.hidden()) {
                hiddenNow.add(target.getUniqueId());
            }
        }

        Collection<? extends Player> viewers = Bukkit.getOnlinePlayers();
        Set<UUID> newlyHidden = new HashSet<>(hiddenNow);
        newlyHidden.removeAll(hiddenTabPlayers);
        Set<UUID> noLongerHidden = new HashSet<>(hiddenTabPlayers);
        noLongerHidden.removeAll(hiddenNow);

        for (Player viewer : viewers) {
            for (UUID hiddenId : newlyHidden) {
                Player hiddenPlayer = Bukkit.getPlayer(hiddenId);
                if (hiddenPlayer != null) {
                    viewer.unlistPlayer(hiddenPlayer);
                }
            }
        }

        for (Player viewer : viewers) {
            for (UUID visibleId : noLongerHidden) {
                Player visiblePlayer = Bukkit.getPlayer(visibleId);
                if (visiblePlayer != null) {
                    viewer.listPlayer(visiblePlayer);
                }
            }
        }

        hiddenTabPlayers.clear();
        hiddenTabPlayers.addAll(hiddenNow);
    }

    private void restoreAllTabEntries() {
        if (hiddenTabPlayers.isEmpty()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (UUID hiddenId : hiddenTabPlayers) {
                Player hiddenPlayer = Bukkit.getPlayer(hiddenId);
                if (hiddenPlayer != null) {
                    viewer.listPlayer(hiddenPlayer);
                }
            }
        }
        hiddenTabPlayers.clear();
    }

    private boolean shouldHideFromTab(Player player) {
        String expression = config.tabHidePlayerIf();
        if (expression == null || expression.isBlank()) {
            return false;
        }
        String resolved = placeholderService.apply(player, expression);
        return isTruthy(resolved);
    }

    private PlayerTabState buildState(Player player) {
        String headerText = String.join("\n", formatter.toMiniMessageLines(player, config.headerLines()));
        String footerText = String.join("\n", formatter.toMiniMessageLines(player, config.footerLines()));
        String playerListNameText = null;
        if (config.playerListNameFormat() != null && !config.playerListNameFormat().isBlank()) {
            playerListNameText = formatter.toMiniMessageText(player, config.playerListNameFormat());
        }

        Integer listOrder = null;
        if (config.sortingEnabled()) {
            int rank = resolveRank(player);
            int order = config.sortingDescending() ? ORDER_BASE - rank : ORDER_BASE + rank;
            listOrder = clamp(order, ORDER_MIN, ORDER_MAX);
        }

        return new PlayerTabState(headerText, footerText, playerListNameText, listOrder, shouldHideFromTab(player));
    }

    private boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase()) {
            case "true", "1", "yes", "y", "on" -> true;
            default -> false;
        };
    }

    private record PlayerTabState(
        String headerText,
        String footerText,
        String playerListNameText,
        Integer listOrder,
        boolean hidden
    ) {
    }
}
