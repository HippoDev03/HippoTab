package net.mwtw.hippoNick;

import net.mwtw.hippoNick.command.NickAdminCommand;
import net.mwtw.hippoNick.command.NickCommand;
import net.mwtw.hippoNick.command.RealNameCommand;
import net.mwtw.hippoNick.gui.NickGuiService;
import net.mwtw.hippoNick.gui.RealNameGuiService;
import net.mwtw.hippoNick.listener.CommandNicknameBridgeListener;
import net.mwtw.hippoNick.listener.NametagLifecycleListener;
import net.mwtw.hippoNick.listener.PlayerLifecycleListener;
import net.mwtw.hippoNick.message.Messages;
import net.mwtw.hippoNick.papi.HippoNickPlaceholderExpansion;
import net.mwtw.hippoNick.service.LuckPermsRankService;
import net.mwtw.hippoNick.service.NametagService;
import net.mwtw.hippoNick.service.NickedActionBarService;
import net.mwtw.hippoNick.service.NickManager;
import net.mwtw.hippoNick.service.PacketNametagService;
import net.mwtw.hippoNick.service.RankService;
import net.mwtw.hippoNick.service.TabPacketNameService;
import net.mwtw.hippoNick.storage.CachingNickRepository;
import net.mwtw.hippoNick.storage.FileNickRepository;
import net.mwtw.hippoNick.storage.MariaDbNickRepository;
import net.mwtw.hippoNick.storage.NickRepository;
import net.mwtw.hippoNick.storage.StorageType;
import net.mwtw.hippoTab.TabModule;
import net.mwtw.hippoTab.command.HippoTabCommand;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Single plugin main: boots the tab-list subsystem ({@link TabModule}) and the nick
 * subsystem (storage, rank service, overhead nametag, commands, GUIs, skins). They
 * share one {@link net.mwtw.hippoTab.service.PlaceholderService} and one
 * {@link TabTextFormatter}.
 */
public final class Core extends JavaPlugin {
    private TabModule tabModule;
    private NickRepository nickRepository;
    private RankService rankService;
    private NametagService nametagService;
    private NickManager nickManager;
    private NickGuiService nickGuiService;
    private RealNameGuiService realNameGuiService;
    private Messages messages;
    private HippoNickPlaceholderExpansion placeholderExpansion;
    private NickedActionBarService nickedActionBarService;
    private TabPacketNameService tabPacketNameService;
    private PacketNametagService packetNametagService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.messages = new Messages(this);

        // Tab subsystem first — it creates the shared PlaceholderService.
        this.tabModule = new TabModule(this);
        this.tabModule.start();
        TabTextFormatter formatter = new TabTextFormatter(tabModule.placeholderService());

        this.rankService = new LuckPermsRankService(this);
        this.packetNametagService = new PacketNametagService(this, formatter);
        this.nametagService = packetNametagService;

        boolean nickEnabled = getConfig().getBoolean("nick.enabled", true);
        if (nickEnabled) {
            if (!getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
                getLogger().warning("nick.enabled=true but LuckPerms is not installed; ranks fall back to 'default'.");
            }
            this.nickRepository = createRepository();
            this.nickManager = new NickManager(this, nickRepository, rankService, nametagService);
            this.packetNametagService.setNickManager(nickManager);
            // Tab/below-name use the same client-visible (nicked) name for scoreboard entries.
            this.tabModule.clientNameResolver().set(nickManager::resolvePacketProfileName);
            this.nickGuiService = new NickGuiService(this, nickManager, rankService, messages);
            this.realNameGuiService = new RealNameGuiService(this, nickManager, messages);
            this.nickedActionBarService = new NickedActionBarService(this, nickManager, rankService, messages);
            this.tabPacketNameService = new TabPacketNameService(this, nickManager, rankService);
            registerNickCommands();
            getServer().getPluginManager().registerEvents(nickGuiService, this);
            getServer().getPluginManager().registerEvents(realNameGuiService, this);
            getServer().getPluginManager().registerEvents(new PlayerLifecycleListener(this, nickManager), this);
            getServer().getPluginManager().registerEvents(new CommandNicknameBridgeListener(nickManager), this);
            registerPlaceholderExpansion();
            nickedActionBarService.start();
            tabPacketNameService.startIfAvailable();
        }

        registerHippoTabCommand();
        getServer().getPluginManager().registerEvents(new NametagLifecycleListener(this, packetNametagService), this);
        packetNametagService.start();

        if (nickManager != null) {
            nickManager.applyDisplayToOnlinePlayers();
        }
        getLogger().info("HippoTab enabled (nick " + (nickEnabled ? "on" : "off") + ").");
    }

    @Override
    public void onDisable() {
        if (nametagService != null) {
            nametagService.shutdown();
        }
        if (nickedActionBarService != null) {
            nickedActionBarService.stop();
        }
        if (tabPacketNameService != null) {
            tabPacketNameService.stop();
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        if (nickRepository != null) {
            nickRepository.close();
        }
        if (tabModule != null) {
            tabModule.stop();
        }
        getLogger().info("HippoTab (tab + nick) disabled.");
    }

    /** Immediately refresh a player's tab entry after a nick change (same-plugin call). */
    public void refreshTab(Player player) {
        if (tabModule != null) {
            tabModule.refreshPlayer(player);
        }
    }

    private NickRepository createRepository() {
        String rawType = getConfig().getString("storage.type", "file");
        StorageType type = StorageType.from(rawType);
        try {
            NickRepository backend = type == StorageType.MARIADB
                    ? new MariaDbNickRepository(this)
                    : new FileNickRepository(defaultDataFile());
            return new CachingNickRepository(backend);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize storage backend: " + type, exception);
        }
    }

    private Path defaultDataFile() {
        String configured = getConfig().getString("storage.file.name", "nick-data.yml");
        return getDataFolder().toPath().resolve(configured);
    }

    private void registerNickCommands() {
        NickCommand nickCommand = new NickCommand(this, nickManager, nickGuiService, messages);
        RealNameCommand realNameCommand = new RealNameCommand(nickManager, messages, realNameGuiService);
        NickAdminCommand nickAdminCommand = new NickAdminCommand(this, nickManager, rankService, nickRepository, messages);

        requireCommand("nick").setExecutor(nickCommand);
        requireCommand("nick").setTabCompleter(nickCommand);
        requireCommand("realname").setExecutor(realNameCommand);
        requireCommand("realname").setTabCompleter(realNameCommand);
        requireCommand("nickadmin").setExecutor(nickAdminCommand);
        requireCommand("nickadmin").setTabCompleter(nickAdminCommand);
        requireCommand("unnick").setExecutor(nickCommand);
        requireCommand("unnick").setTabCompleter(nickCommand);
    }

    private void registerHippoTabCommand() {
        HippoTabCommand hippoTabCommand = new HippoTabCommand(this);
        requireCommand("hippotab").setExecutor(hippoTabCommand);
        requireCommand("hippotab").setTabCompleter(hippoTabCommand);
    }

    private org.bukkit.command.PluginCommand requireCommand(String name) {
        org.bukkit.command.PluginCommand command = getCommand(name.toLowerCase(Locale.ROOT));
        if (command == null) {
            throw new IllegalStateException("Missing command in plugin.yml: " + name);
        }
        return command;
    }

    private void registerPlaceholderExpansion() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        this.placeholderExpansion = new HippoNickPlaceholderExpansion(this, nickManager, rankService);
        this.placeholderExpansion.register();
        getLogger().info("Registered PlaceholderAPI expansion: %hipponick_*%");
    }

    public void reloadRuntimeState() {
        reloadConfig();
        messages.load();
        if (tabModule != null) {
            tabModule.reload();
        }
        if (nickedActionBarService != null) {
            nickedActionBarService.stop();
            nickedActionBarService.start();
        }
        if (packetNametagService != null) {
            packetNametagService.stop();
            packetNametagService.start();
        }
        if (nickManager != null) {
            nickManager.applyDisplayToOnlinePlayers();
        }
    }
}
