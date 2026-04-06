package net.mwtw.hippoTab;

import net.mwtw.hippoTab.command.HippoTabCommand;
import net.mwtw.hippoTab.config.TabConfig;
import net.mwtw.hippoTab.listener.PlayerConnectionListener;
import net.mwtw.hippoTab.service.PlaceholderService;
import net.mwtw.hippoTab.service.TabService;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class Core extends JavaPlugin {
    private TabService tabService;
    private PlaceholderService placeholderService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        placeholderService = new PlaceholderService(this);
        reloadPluginState();

        Bukkit.getPluginManager().registerEvents(new PlayerConnectionListener(tabService), this);

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
    }

    public void reloadPluginState() {
        reloadConfig();

        if (tabService != null) {
            tabService.stop();
        }

        TabConfig tabConfig = TabConfig.from(this);
        TabTextFormatter formatter = new TabTextFormatter(placeholderService);
        tabService = new TabService(this, tabConfig, formatter, placeholderService);
        tabService.start();
    }
}
