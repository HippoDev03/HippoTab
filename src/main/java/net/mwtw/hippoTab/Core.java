package net.mwtw.hippoTab;

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
    private TabService tabService;
    private NametagService nametagService;
    private SidebarScoreboardService sidebarScoreboardService;
    private PlaceholderService placeholderService;
    private ConditionParser conditionParser;
    private ClientTeamStateService clientTeamStateService;
    private RedisTabSyncService redisTabSyncService;
    private PlayerConnectionListener playerConnectionListener;

    @Override
    public void onEnable() {
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
        if (tabService != null) {
            tabService.stop();
        }
        if (nametagService != null) {
            nametagService.stop();
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
        if (nametagService != null) {
            nametagService.stop();
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
        nametagService = new NametagService(this, tabConfig, formatter, placeholderService, conditionParser);
        sidebarScoreboardService = new SidebarScoreboardService(this, tabConfig, formatter);
        redisTabSyncService = new RedisTabSyncService(this, tabConfig, formatter, placeholderService);

        placeholderService.setConditionalPlaceholders(tabConfig.conditionalPlaceholders());
        placeholderService.setNametagService(nametagService);

        if (playerConnectionListener != null) {
            HandlerList.unregisterAll(playerConnectionListener);
        }
        playerConnectionListener = new PlayerConnectionListener(
            tabService,
            nametagService,
            sidebarScoreboardService,
            clientTeamStateService
        );
        Bukkit.getPluginManager().registerEvents(playerConnectionListener, this);

        nametagService.start();
        sidebarScoreboardService.start();
        tabService.start();
        redisTabSyncService.start();
    }

    private YamlConfiguration loadConditionalPlaceholderConfig() {
        File file = new File(getDataFolder(), "conditional-placeholders.yml");
        return YamlConfiguration.loadConfiguration(file);
    }
}
