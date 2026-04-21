package net.mwtw.hippoTab;

import dev.faststats.bukkit.BukkitMetrics;
import dev.faststats.core.ErrorTracker;
import net.mwtw.hippoTab.command.HippoTabCommand;
import net.mwtw.hippoTab.config.TabConfig;
import net.mwtw.hippoTab.listener.PlayerConnectionListener;
import net.mwtw.hippoTab.service.*;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class Core extends JavaPlugin {
    public static final ErrorTracker ERROR_TRACKER = ErrorTracker.contextAware();

    private final BukkitMetrics metrics = BukkitMetrics.factory()
        .token("590c4db165c1f8ad6d89b3e2a0b4fa07")
        .errorTracker(ERROR_TRACKER)
        .create(this);

    private TabService tabService;
    private NameTagService nameTagService;
    private BelowNameService belowNameService;
    private SidebarScoreboardService sidebarScoreboardService;
    private PlaceholderService placeholderService;
    private ConditionParser conditionParser;
    private ClientTeamStateService clientTeamStateService;
    private RedisTabSyncService redisTabSyncService;
    private PlayerConnectionListener playerConnectionListener;

    @Override
    public void onEnable() {
        metrics.ready();
        saveDefaultConfig();
        saveResource("conditional-placeholders.yml", false);
        placeholderService = new PlaceholderService(this);
        conditionParser = new ConditionParser(placeholderService);
        clientTeamStateService = new ClientTeamStateService(this);
        clientTeamStateService.start();
        reloadPluginState();

        PluginCommand command = getCommand("hippotab");
        if (command == null) {
            throw new IllegalStateException("Command hippotab is not defined in plugin.yml");
        }
        HippoTabCommand hippoTabCommand = new HippoTabCommand(this);
        command.setExecutor(hippoTabCommand);
        command.setTabCompleter(hippoTabCommand);
    }

    @Override
    public void onDisable() {
        metrics.shutdown();

        if (tabService != null) {
            tabService.stop();
        }
        if (nameTagService != null) {
            nameTagService.cleanup();
        }
        if (belowNameService != null) {
            belowNameService.stop();
        }
        if (sidebarScoreboardService != null) {
            sidebarScoreboardService.stop();
        }
        if (clientTeamStateService != null) {
            clientTeamStateService.stop();
        }
        if (redisTabSyncService != null) {
            redisTabSyncService.stop();
        }
        if (placeholderService != null) {
            placeholderService.unregister();
        }
    }

    public void reloadPluginState() {
        reloadConfig();

        if (tabService != null) {
            tabService.stop();
        }
        if (nameTagService != null) {
            nameTagService.cleanup();
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

        TabConfig tabConfig = TabConfig.from(this).withConditionalPlaceholders(loadConditionalPlaceholderConfig());
        TabTextFormatter formatter = new TabTextFormatter(placeholderService);
        
        tabService = new TabService(this, tabConfig, formatter, placeholderService);
        nameTagService = new NameTagService(this, tabConfig, formatter, conditionParser);
        belowNameService = new BelowNameService(this, tabConfig, formatter, placeholderService, conditionParser);
        sidebarScoreboardService = new SidebarScoreboardService(this, tabConfig, formatter);
        redisTabSyncService = new RedisTabSyncService(this, tabConfig, formatter, placeholderService);

        placeholderService.setConditionalPlaceholders(tabConfig.conditionalPlaceholders());
        tabService.setNameTagService(nameTagService);
        tabService.setBelowNameService(belowNameService);
        placeholderService.setNameTagService(nameTagService);

        if (playerConnectionListener != null) {
            HandlerList.unregisterAll(playerConnectionListener);
        }
        playerConnectionListener = new PlayerConnectionListener(
            tabService,
            nameTagService,
            belowNameService,
            sidebarScoreboardService,
            clientTeamStateService
        );
        Bukkit.getPluginManager().registerEvents(playerConnectionListener, this);

        nameTagService.start();
        belowNameService.start();
        sidebarScoreboardService.start();
        tabService.start();
        redisTabSyncService.start();
    }

    private YamlConfiguration loadConditionalPlaceholderConfig() {
        File file = new File(getDataFolder(), "conditional-placeholders.yml");
        return YamlConfiguration.loadConfiguration(file);
    }
}
