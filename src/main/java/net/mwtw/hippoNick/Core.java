package net.mwtw.hippoNick;

import com.zaxxer.hikari.HikariDataSource;
import net.mwtw.hippoNick.command.NickAdminCommand;
import net.mwtw.hippoNick.command.NickCommand;
import net.mwtw.hippoNick.command.NickHistoryCommand;
import net.mwtw.hippoNick.command.RealNameCommand;
import net.mwtw.hippoNick.gui.NickGuiService;
import net.mwtw.hippoNick.gui.RealNameGuiService;
import net.mwtw.hippoNick.history.NickHistoryService;
import net.mwtw.hippoNick.listener.CommandNicknameBridgeListener;
import net.mwtw.hippoNick.listener.NametagLifecycleListener;
import net.mwtw.hippoNick.listener.PlayerLifecycleListener;
import net.mwtw.hippoNick.message.Messages;
import net.mwtw.hippoNick.papi.HippoNickPlaceholderExpansion;
import net.mwtw.hippoNick.service.LuckPermsRankService;
import net.mwtw.hippoNick.service.NametagService;
import net.mwtw.hippoNick.service.NetworkNickDirectory;
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
import net.mwtw.hippoTab.chat.ChatListener;
import net.mwtw.hippoTab.chat.ChatService;
import net.mwtw.hippoTab.chatfilter.ChatFilterListener;
import net.mwtw.hippoTab.chatfilter.DiscordNotifier;
import net.mwtw.hippoTab.chatfilter.FilterManager;
import net.mwtw.hippoTab.chatfilter.SpamGuard;
import net.mwtw.hippoTab.command.ChatFilterCommand;
import net.mwtw.hippoTab.command.HippoTabCommand;
import net.mwtw.hippoTab.command.JoinQuitCommand;
import net.mwtw.hippoTab.command.SpyCommand;
import net.mwtw.hippoTab.command.ToggleChatCommand;
import net.mwtw.hippoTab.discord.DiscordLogService;
import net.mwtw.hippoTab.discord.DiscordWebhook;
import net.mwtw.hippoTab.joinquit.JoinQuitListener;
import net.mwtw.hippoTab.joinquit.JoinQuitService;
import net.mwtw.hippoTab.pm.PrivateMessageListener;
import net.mwtw.hippoTab.pm.SpyService;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Single plugin main: boots the tab-list subsystem ({@link TabModule}), the nick
 * subsystem (storage, rank service, overhead nametag, commands, GUIs, skins, history)
 * and the chat features (format, filter, private-message spy, Discord log). They share
 * one {@link net.mwtw.hippoTab.service.PlaceholderService} and one
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
    private JoinQuitService joinQuitService;
    private ChatService chatService;
    private FilterManager filterManager;
    private DiscordWebhook discordWebhook;
    private DiscordLogService discordLog;
    private NickHistoryService nickHistory;
    private NetworkNickDirectory nickDirectory;
    private SpyService spyService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.messages = new Messages(this);

        this.discordWebhook = new DiscordWebhook(this);
        this.discordWebhook.start();
        this.discordLog = new DiscordLogService(this, discordWebhook, this::realName, this::nickname);
        getServer().getPluginManager().registerEvents(discordLog, this);

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
            this.nickHistory = new NickHistoryService(this, discordLog, mariaDataSource());
            this.nickManager.setHistory(nickHistory);
            this.packetNametagService.setNickManager(nickManager);
            // Tab/below-name use the same client-visible (nicked) name for scoreboard entries.
            this.tabModule.clientNameResolver().set(nickManager::resolvePacketProfileName);
            this.nickGuiService = new NickGuiService(this, nickManager, rankService, messages);
            this.nickDirectory = new NetworkNickDirectory(this, nickManager, discordLog::serverName);
            this.nickDirectory.start();
            this.realNameGuiService = new RealNameGuiService(this, nickManager, messages, nickDirectory);
            this.nickedActionBarService = new NickedActionBarService(this, nickManager, rankService, messages);
            this.tabPacketNameService = new TabPacketNameService(this, nickManager, rankService);
            registerNickCommands();
            getServer().getPluginManager().registerEvents(nickGuiService, this);
            getServer().getPluginManager().registerEvents(realNameGuiService, this);
            getServer().getPluginManager().registerEvents(new PlayerLifecycleListener(this, nickManager, messages), this);
            getServer().getPluginManager().registerEvents(new CommandNicknameBridgeListener(nickManager), this);
            registerPlaceholderExpansion();
            nickedActionBarService.start();
            tabPacketNameService.startIfAvailable();
        }

        HippoTabCommand hippoTabCommand = new HippoTabCommand(this, messages);
        requireCommand("hippotab").setExecutor(hippoTabCommand);
        requireCommand("hippotab").setTabCompleter(hippoTabCommand);

        this.joinQuitService = new JoinQuitService(this, formatter);
        getServer().getPluginManager().registerEvents(new JoinQuitListener(joinQuitService), this);
        JoinQuitCommand joinQuitCommand = new JoinQuitCommand(joinQuitService, messages);
        requireCommand("connmsg").setExecutor(joinQuitCommand);
        requireCommand("connmsg").setTabCompleter(joinQuitCommand);

        this.chatService = new ChatService(this, formatter);
        getServer().getPluginManager().registerEvents(new ChatListener(chatService), this);
        ToggleChatCommand toggleChatCommand = new ToggleChatCommand(chatService, messages);
        requireCommand("togglechat").setExecutor(toggleChatCommand);
        requireCommand("togglechat").setTabCompleter(toggleChatCommand);

        registerChatFilter();

        this.spyService = new SpyService(this, messages);
        this.spyService.start();
        net.mwtw.hippoTab.api.HippoTabApi.bindSpy(spyService, discordLog);
        getServer().getPluginManager().registerEvents(
                new PrivateMessageListener(this, spyService, discordLog, this::realName), this);
        SpyCommand spyCommand = new SpyCommand(spyService, messages);
        requireCommand("spy").setExecutor(spyCommand);
        requireCommand("spy").setTabCompleter(spyCommand);

        getServer().getPluginManager().registerEvents(new NametagLifecycleListener(this, packetNametagService), this);
        packetNametagService.start();

        if (nickManager != null) {
            nickManager.applyDisplayToOnlinePlayers();
        }
        discordLog.serverStatus(true);
        getLogger().info("HippoTab enabled (nick " + (nickEnabled ? "on" : "off") + ").");
    }

    /** See {@link PacketNametagService#reassert}. Offline players are skipped. */
    public void reassertNametags(java.util.Collection<? extends org.bukkit.entity.Player> players) {
        if (packetNametagService != null) {
            packetNametagService.reassert(players.stream().filter(org.bukkit.entity.Player::isOnline).toList());
        }
    }

    @Override
    public void onDisable() {
        // First, while the packet rewriter is still registered: hand renamed profiles back.
        if (nickManager != null) {
            nickManager.shutdown();
        }
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
        if (spyService != null) {
            spyService.stop();
        }
        if (nickDirectory != null) {
            nickDirectory.stop();
        }
        // Before the repository: the history may share its MariaDB pool.
        if (nickHistory != null) {
            nickHistory.close();
        }
        if (nickRepository != null) {
            nickRepository.close();
        }
        if (tabModule != null) {
            tabModule.stop();
        }
        if (discordWebhook != null) {
            // Queued last, then stop() delivers what is still waiting before the plugin goes away.
            discordLog.serverStatus(false);
            discordWebhook.stop();
        }
        getLogger().info("HippoTab (tab + nick) disabled.");
    }

    /** Immediately refresh a player's tab entry after a nick change (same-plugin call). */
    public void refreshTab(Player player) {
        if (tabModule != null) {
            tabModule.refreshPlayer(player);
        }
    }

    /** The player's login name; player.getName() is the nick while they are nicked. */
    public String realName(Player player) {
        return nickManager != null ? nickManager.realName(player) : player.getName();
    }

    /** The player's current nickname, or null when they have none (or nick is off). */
    public String nickname(Player player) {
        return nickManager != null ? nickManager.nicknameOf(player) : null;
    }

    private NickRepository createRepository() {
        String rawType = getConfig().getString("storage.type", "file");
        StorageType type = StorageType.from(rawType);
        try {
            NickRepository backend = type == StorageType.MARIADB
                    ? new MariaDbNickRepository(this)
                    : new FileNickRepository(defaultDataFile());
            return new CachingNickRepository(backend, getLogger());
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize storage backend: " + type, exception);
        }
    }

    /** The MariaDB pool of the nick storage, or null when nicks are stored in a file. */
    private HikariDataSource mariaDataSource() {
        if (nickRepository instanceof CachingNickRepository caching
                && caching.delegate() instanceof MariaDbNickRepository maria) {
            return maria.dataSource();
        }
        return null;
    }

    private Path defaultDataFile() {
        String configured = getConfig().getString("storage.file.name", "nick-data.yml");
        return getDataFolder().toPath().resolve(configured);
    }

    private void registerNickCommands() {
        NickCommand nickCommand = new NickCommand(this, nickManager, nickGuiService, messages);
        RealNameCommand realNameCommand = new RealNameCommand(nickManager, messages, realNameGuiService, nickDirectory);
        NickAdminCommand nickAdminCommand = new NickAdminCommand(this, nickManager, rankService, nickRepository, messages);
        NickHistoryCommand nickHistoryCommand = new NickHistoryCommand(nickHistory, nickManager, messages);

        requireCommand("nick").setExecutor(nickCommand);
        requireCommand("nick").setTabCompleter(nickCommand);
        requireCommand("realname").setExecutor(realNameCommand);
        requireCommand("realname").setTabCompleter(realNameCommand);
        requireCommand("nickadmin").setExecutor(nickAdminCommand);
        requireCommand("nickadmin").setTabCompleter(nickAdminCommand);
        requireCommand("unnick").setExecutor(nickCommand);
        requireCommand("unnick").setTabCompleter(nickCommand);
        requireCommand("nickhistory").setExecutor(nickHistoryCommand);
        requireCommand("nickhistory").setTabCompleter(nickHistoryCommand);
    }

    private void registerChatFilter() {
        // The standalone HippoChatFilter plugin does the same job; running both would
        // filter, rate-limit and report every message twice.
        if (getServer().getPluginManager().getPlugin("HippoChatFilter") != null) {
            getLogger().warning("The standalone HippoChatFilter plugin is installed; the built-in chat filter stays off. "
                    + "Remove HippoChatFilter to use the built-in one.");
            requireCommand("hchat").setExecutor((sender, cmd, label, args) -> {
                messages.send(sender, "chatfilter.standalone-installed");
                return true;
            });
            return;
        }
        this.filterManager = new FilterManager(this);
        filterManager.reload();
        getServer().getPluginManager().registerEvents(new ChatFilterListener(filterManager,
                new DiscordNotifier(discordWebhook), new SpamGuard(), discordLog::plainName, messages, getLogger()), this);
        ChatFilterCommand command = new ChatFilterCommand(this, filterManager, messages);
        requireCommand("hchat").setExecutor(command);
        requireCommand("hchat").setTabCompleter(command);
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
        if (filterManager != null) {
            filterManager.reload();
        }
        if (spyService != null) {
            spyService.stop();
            spyService.start();
        }
        if (nickDirectory != null) {
            nickDirectory.stop();
            nickDirectory.start();
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
