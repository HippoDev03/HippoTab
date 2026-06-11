package net.mwtw.hippoTab;

import dev.faststats.bukkit.BukkitMetrics;
import dev.faststats.core.ErrorTracker;
import net.mwtw.hippoTab.config.TabConfig;
import net.mwtw.hippoTab.listener.PlayerConnectionListener;
import net.mwtw.hippoTab.service.BelowNameService;
import net.mwtw.hippoTab.service.ClientNameResolver;
import net.mwtw.hippoTab.service.ConditionParser;
import net.mwtw.hippoTab.service.PlaceholderService;
import net.mwtw.hippoTab.service.RedisTabSyncService;
import net.mwtw.hippoTab.service.SidebarScoreboardService;
import net.mwtw.hippoTab.service.TabService;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * The tab-list subsystem (tab list, sorting, header/footer, belowname, sidebar,
 * redis sync) extracted from the former HippoTab plugin. After the merge it is no
 * longer the plugin main class — it is owned and driven by the single plugin
 * {@code Core}. The overhead nametag is NOT here; it is owned by the nick
 * subsystem's {@code PacketNametagService}.
 */
public final class TabModule {
    public static final ErrorTracker ERROR_TRACKER = ErrorTracker.contextAware();

    private final JavaPlugin plugin;
    private final BukkitMetrics metrics;
    private final ClientNameResolver nameResolver = new ClientNameResolver();

    private TabService tabService;
    private BelowNameService belowNameService;
    private SidebarScoreboardService sidebarScoreboardService;
    private PlaceholderService placeholderService;
    private ConditionParser conditionParser;
    private RedisTabSyncService redisTabSyncService;
    private PlayerConnectionListener playerConnectionListener;

    public TabModule(JavaPlugin plugin) {
        this.plugin = plugin;
        this.metrics = BukkitMetrics.factory()
            .token("590c4db165c1f8ad6d89b3e2a0b4fa07")
            .errorTracker(ERROR_TRACKER)
            .create(plugin);
    }

    public PlaceholderService placeholderService() {
        return placeholderService;
    }

    /** The shared client-name resolver — the nick subsystem installs its resolver here. */
    public ClientNameResolver clientNameResolver() {
        return nameResolver;
    }

    public void start() {
        metrics.ready();
        plugin.saveResource("conditional-placeholders.yml", false);
        placeholderService = new PlaceholderService(plugin);
        conditionParser = new ConditionParser(placeholderService);
        reload();
    }

    /** Rebuilds the tab services from the current config. Caller is responsible for reloadConfig(). */
    public void reload() {
        if (tabService != null) {
            tabService.stop();
        }
        if (belowNameService != null) {
            belowNameService.stop();
        }
        if (sidebarScoreboardService != null) {
            sidebarScoreboardService.stop();
        }
        if (redisTabSyncService != null) {
            redisTabSyncService.stop();
        }

        TabConfig tabConfig = TabConfig.from(plugin).withConditionalPlaceholders(loadConditionalPlaceholderConfig());
        TabTextFormatter formatter = new TabTextFormatter(placeholderService);

        tabService = new TabService(plugin, tabConfig, formatter, placeholderService);
        belowNameService = new BelowNameService(plugin, tabConfig, formatter, placeholderService, conditionParser, nameResolver);
        sidebarScoreboardService = new SidebarScoreboardService(plugin, tabConfig, formatter);
        redisTabSyncService = new RedisTabSyncService(plugin, tabConfig, formatter, placeholderService, nameResolver);

        placeholderService.setConditionalPlaceholders(tabConfig.conditionalPlaceholders());
        tabService.setBelowNameService(belowNameService);

        if (playerConnectionListener != null) {
            HandlerList.unregisterAll(playerConnectionListener);
        }
        playerConnectionListener = new PlayerConnectionListener(
            plugin, tabService, belowNameService, sidebarScoreboardService, redisTabSyncService, placeholderService);
        Bukkit.getPluginManager().registerEvents(playerConnectionListener, plugin);

        belowNameService.start();
        sidebarScoreboardService.start();
        tabService.start();
        redisTabSyncService.start();
    }

    public void stop() {
        metrics.shutdown();
        if (tabService != null) {
            tabService.stop();
        }
        if (belowNameService != null) {
            belowNameService.stop();
        }
        if (sidebarScoreboardService != null) {
            sidebarScoreboardService.stop();
        }
        if (redisTabSyncService != null) {
            redisTabSyncService.stop();
        }
        if (placeholderService != null) {
            placeholderService.unregister();
        }
        if (playerConnectionListener != null) {
            HandlerList.unregisterAll(playerConnectionListener);
        }
    }

    /** Immediately refresh a player's tab entry (e.g. after a nick change). */
    public void refreshPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (tabService != null) {
                tabService.refreshPlayer(player);
            }
            if (belowNameService != null) {
                belowNameService.refreshPlayer(player);
            }
        });
    }

    private YamlConfiguration loadConditionalPlaceholderConfig() {
        File file = new File(plugin.getDataFolder(), "conditional-placeholders.yml");
        return YamlConfiguration.loadConfiguration(file);
    }
}
