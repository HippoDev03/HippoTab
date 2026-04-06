package net.mwtw.hippoTab.service;

import net.kyori.adventure.text.Component;
import net.mwtw.hippoTab.config.TabConfig;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

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
    }

    public void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshPlayer(player);
        }
        applySorting();
    }

    public void refreshPlayer(Player player) {
        Component header = formatter.linesToComponent(player, config.headerLines());
        Component footer = formatter.linesToComponent(player, config.footerLines());
        player.sendPlayerListHeaderAndFooter(header, footer);

        if (config.playerListNameFormat() != null && !config.playerListNameFormat().isBlank()) {
            player.playerListName(formatter.toComponent(player, config.playerListNameFormat()));
        }
    }

    public void applySorting() {
        if (!config.sortingEnabled()) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            int rank = resolveRank(player);
            int order = config.sortingDescending() ? ORDER_BASE - rank : ORDER_BASE + rank;
            player.setPlayerListOrder(clamp(order, ORDER_MIN, ORDER_MAX));
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
}
