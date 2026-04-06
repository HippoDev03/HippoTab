package net.mwtw.hippoTab;

import net.mwtw.hippoTab.command.HippoTabCommand;
import net.mwtw.hippoTab.config.TabConfig;
import net.mwtw.hippoTab.listener.PlayerConnectionListener;
import net.mwtw.hippoTab.service.*;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class Core extends JavaPlugin {
    private TabService tabService;
    private NameTagService nameTagService;
    private BelowNameService belowNameService;
    private PlaceholderService placeholderService;
    private ConditionParser conditionParser;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        placeholderService = new PlaceholderService(this);
        conditionParser = new ConditionParser(placeholderService);
        reloadPluginState();

        Bukkit.getPluginManager().registerEvents(new PlayerConnectionListener(tabService, nameTagService, belowNameService), this);

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
        if (nameTagService != null) {
            nameTagService.cleanup();
        }
        if (belowNameService != null) {
            belowNameService.stop();
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

        TabConfig tabConfig = TabConfig.from(this);
        TabTextFormatter formatter = new TabTextFormatter(placeholderService);
        
        tabService = new TabService(this, tabConfig, formatter, placeholderService);
        nameTagService = new NameTagService(this, tabConfig, formatter, conditionParser);
        belowNameService = new BelowNameService(this, tabConfig, formatter, placeholderService, conditionParser);
        
        tabService.setNameTagService(nameTagService);
        tabService.setBelowNameService(belowNameService);
        placeholderService.setNameTagService(nameTagService);
        
        nameTagService.start();
        belowNameService.start();
        tabService.start();
    }
}
