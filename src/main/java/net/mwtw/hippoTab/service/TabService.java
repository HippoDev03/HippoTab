package net.mwtw.hippoTab.service;

import net.kyori.adventure.text.Component;
import net.mwtw.hippoTab.config.TabConfig;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
    private BelowNameService belowNameService;
    // Per-viewer set of players currently unlisted from that viewer's tab. Hiding is
    // per-viewer so staff with the bypass permission still see hidden (vanished) players.
    private final Map<UUID, Set<UUID>> hiddenByViewer = new HashMap<>();
    private final Map<UUID, PlayerTabState> playerStates = new ConcurrentHashMap<>();

    private BukkitTask updateTask;

    public TabService(JavaPlugin plugin, TabConfig config, TabTextFormatter formatter, PlaceholderService placeholderService) {
        this.plugin = plugin;
        this.config = config;
        this.formatter = formatter;
        this.placeholderService = placeholderService;
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
        updateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
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

        List<Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        long interval = config.updateIntervalTicks();
        long spread = Math.min(players.size(), Math.min(5, Math.max(1, interval / 2)));

        long maxDelay = 0;
        for (int i = 0; i < players.size(); i++) {
            final Player p = players.get(i);
            long delay = i % spread;
            if (delay > maxDelay) maxDelay = delay;
            if (delay == 0) {
                refreshPlayer(p);
            } else {
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                    if (p.isOnline()) refreshPlayer(p);
                }, delay);
            }
        }

        if (maxDelay == 0) {
            syncTabVisibility();
        } else {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::syncTabVisibility, maxDelay + 1);
        }
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
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            Player onlinePlayer = Bukkit.getPlayer(playerId);
            if (onlinePlayer == null || !onlinePlayer.isOnline()) {
                return;
            }

            playerStates.remove(playerId);
            refreshPlayer(onlinePlayer);
            syncTabVisibility();
        }, 1L);
    }

    // synchronized with syncTabVisibility: hiddenByViewer is a plain HashMap.
    public synchronized void removePlayer(Player player) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUniqueId();
        playerStates.remove(playerId);
        // Drop the leaving player as a viewer, and as a hidden entry in every other viewer's set.
        hiddenByViewer.remove(playerId);
        for (Set<UUID> hidden : hiddenByViewer.values()) {
            hidden.remove(playerId);
        }
    }

    /** Re-apply per-viewer tab visibility right now (after refreshPlayer on the changed players). */
    public void syncVisibilityNow() {
        if (config.tabEnabled()) {
            syncTabVisibility();
        }
    }

    /**
     * The player's tab view group (config tab.view-group), or "" when they have none.
     * A viewer with a view group only sees players whose member group equals it; see syncTabVisibility.
     */
    public String viewGroup(UUID playerId) {
        PlayerTabState state = playerStates.get(playerId);
        return state == null ? "" : state.viewGroup();
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

    // synchronized: runs from the periodic refresh and from HippoTabApi, both async.
    private synchronized void syncTabVisibility() {
        Set<UUID> hiddenNow = new HashSet<>();
        Map<UUID, String> viewGroups = new HashMap<>();
        Map<UUID, String> memberGroups = new HashMap<>();
        for (Player target : Bukkit.getOnlinePlayers()) {
            PlayerTabState state = playerStates.computeIfAbsent(target.getUniqueId(), ignored -> buildState(target));
            if (state.hidden()) {
                hiddenNow.add(target.getUniqueId());
            }
            viewGroups.put(target.getUniqueId(), state.viewGroup());
            memberGroups.put(target.getUniqueId(), state.memberGroup());
        }

        String bypassPermission = config.tabHideBypassPermission();
        Set<UUID> presentViewers = new HashSet<>();

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            presentViewers.add(viewer.getUniqueId());

            // Viewers with the bypass permission (OP by default) see every hidden player;
            // they never have anything unlisted. A player is never hidden from themselves.
            boolean canSeeHidden = bypassPermission == null || bypassPermission.isBlank()
                || viewer.hasPermission(bypassPermission);

            Set<UUID> desiredHidden = new HashSet<>();
            if (!canSeeHidden) {
                for (UUID hiddenId : hiddenNow) {
                    if (!hiddenId.equals(viewer.getUniqueId())) {
                        desiredHidden.add(hiddenId);
                    }
                }
            }

            // View group: a viewer with a view group (e.g. playing or spectating a match) only
            // sees players whose MEMBER group equals it. Viewers without one see everyone.
            // Not affected by the bypass permission — that one is only about vanish.
            String viewerGroup = viewGroups.getOrDefault(viewer.getUniqueId(), "");
            if (!viewerGroup.isEmpty()) {
                for (Map.Entry<UUID, String> entry : memberGroups.entrySet()) {
                    if (!entry.getKey().equals(viewer.getUniqueId()) && !viewerGroup.equals(entry.getValue())) {
                        desiredHidden.add(entry.getKey());
                    }
                }
            }

            Set<UUID> current = hiddenByViewer.computeIfAbsent(viewer.getUniqueId(), ignored -> new HashSet<>());

            // Paper only (un)lists a player the viewer can currently see: unlistPlayer is
            // a no-op and listPlayer throws otherwise (vanish plugins, or the short
            // hide/show a skin refresh does). Track only what was really applied, so the
            // rest is retried on the next sync instead of being assumed done.
            Set<UUID> applied = new HashSet<>();
            for (UUID hiddenId : desiredHidden) {
                if (current.contains(hiddenId)) {
                    applied.add(hiddenId);
                    continue;
                }
                Player hiddenPlayer = Bukkit.getPlayer(hiddenId);
                if (hiddenPlayer != null && viewer.canSee(hiddenPlayer)) {
                    viewer.unlistPlayer(hiddenPlayer);
                    applied.add(hiddenId);
                }
            }

            for (UUID previouslyHidden : current) {
                if (desiredHidden.contains(previouslyHidden)) {
                    continue;
                }
                Player visiblePlayer = Bukkit.getPlayer(previouslyHidden);
                if (visiblePlayer == null) {
                    continue;
                }
                if (!relist(viewer, visiblePlayer)) {
                    // Still unlisted on the server side; keep tracking it.
                    applied.add(previouslyHidden);
                }
            }

            current.clear();
            current.addAll(applied);
        }

        hiddenByViewer.keySet().retainAll(presentViewers);
    }

    /** Lists the target for the viewer again; false when it could not be done right now. */
    private boolean relist(Player viewer, Player target) {
        if (!viewer.canSee(target)) {
            return false;
        }
        try {
            viewer.listPlayer(target);
            return true;
        } catch (IllegalStateException exception) {
            // canSee changed between the check and the call.
            return false;
        }
    }

    private synchronized void restoreAllTabEntries() {
        if (hiddenByViewer.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Set<UUID>> entry : hiddenByViewer.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null) {
                continue;
            }
            for (UUID hiddenId : entry.getValue()) {
                Player hiddenPlayer = Bukkit.getPlayer(hiddenId);
                if (hiddenPlayer != null) {
                    relist(viewer, hiddenPlayer);
                }
            }
        }
        hiddenByViewer.clear();
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

        String viewGroup = resolveGroup(player, config.tabViewGroup());
        // member-group left blank = same as view-group (symmetric groups).
        String memberExpression = config.tabMemberGroup();
        String memberGroup = memberExpression == null || memberExpression.isBlank()
            ? viewGroup : resolveGroup(player, memberExpression);
        return new PlayerTabState(headerText, footerText, playerListNameText, listOrder, shouldHideFromTab(player),
            viewGroup, memberGroup);
    }

    private String resolveGroup(Player player, String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        String resolved = placeholderService.apply(player, expression);
        // An unresolved placeholder (the providing plugin is missing) must not put everyone
        // into one accidental group.
        return resolved == null || resolved.contains("%") ? "" : resolved.trim();
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
        boolean hidden,
        String viewGroup,
        String memberGroup
    ) {
    }
}
