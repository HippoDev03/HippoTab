package net.mwtw.hippoTab.pm;

import net.kyori.adventure.text.Component;
import net.mwtw.hippoNick.message.MessagePlaceholder;
import net.mwtw.hippoNick.message.Messages;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets staff read players' private messages. Each staff member picks a mode with /spy:
 * off, this server only, or the whole network. Network mode relays private messages
 * between servers over Redis pub/sub (the connection settings of {@code redis-sync}).
 */
public final class SpyService {
    public static final String PERMISSION = "hippotab.spy";

    public enum Mode {
        OFF, SERVER, NETWORK;

        public static Mode parse(String raw, Mode fallback) {
            if (raw == null) {
                return fallback;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "off", "none", "false" -> OFF;
                case "server", "local", "on", "true" -> SERVER;
                case "network", "global", "all" -> NETWORK;
                default -> fallback;
            };
        }
    }

    private final JavaPlugin plugin;
    private final Messages messages;
    private final File file;
    private final Map<UUID, Mode> modes = new ConcurrentHashMap<>();

    private volatile boolean running;
    private volatile JedisPool pool;
    private volatile JedisPubSub subscription;
    private volatile String channel = "";
    private volatile String serverId = "";
    private Thread subscriberThread;
    // One warning per outage instead of one every retry.
    private volatile boolean outageLogged;

    public SpyService(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.file = new File(plugin.getDataFolder(), "spy-data.yml");
        load();
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("private-messages.spy.enabled", true);
    }

    /** Whether this server is relaying private messages to and from the network right now. */
    public boolean isNetworkAvailable() {
        return pool != null;
    }

    public Mode defaultMode() {
        return Mode.parse(plugin.getConfig().getString("private-messages.spy.default-mode", "off"), Mode.OFF);
    }

    public Mode mode(UUID staff) {
        Mode mode = modes.get(staff);
        return mode == null ? defaultMode() : mode;
    }

    public void setMode(UUID staff, Mode mode) {
        modes.put(staff, mode);
        save();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public synchronized void start() {
        if (!isEnabled() || !plugin.getConfig().getBoolean("private-messages.spy.network.enabled", false)) {
            return;
        }
        String host = plugin.getConfig().getString("redis-sync.host", "127.0.0.1");
        int port = plugin.getConfig().getInt("redis-sync.port", 6379);
        String username = blankToNull(plugin.getConfig().getString("redis-sync.username", ""));
        String password = blankToNull(plugin.getConfig().getString("redis-sync.password", ""));
        int database = Math.max(0, plugin.getConfig().getInt("redis-sync.database", 0));
        String configuredId = plugin.getConfig().getString("redis-sync.server-id", "");
        serverId = configuredId == null || configuredId.isBlank()
                ? "server-" + plugin.getServer().getPort() : configuredId;
        channel = plugin.getConfig().getString("redis-sync.key-prefix", "hippotab:tab-sync") + ":spy";

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(4);
        poolConfig.setMaxIdle(2);
        // Read timeout 0: the subscriber connection blocks until a message arrives.
        pool = new JedisPool(poolConfig, host, port, 2_000, 0, username, password, database, null);
        running = true;
        subscriberThread = new Thread(this::subscribeLoop, "HippoTab-spy-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        plugin.getLogger().info("Private-message spy network relay enabled (server-id=" + serverId + ").");
    }

    public synchronized void stop() {
        running = false;
        JedisPubSub current = subscription;
        if (current != null) {
            try {
                current.unsubscribe();
            } catch (RuntimeException ignored) {
                // Connection already gone.
            }
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
            subscriberThread = null;
        }
        JedisPool current2 = pool;
        pool = null;
        if (current2 != null) {
            current2.close();
        }
    }

    private void subscribeLoop() {
        while (running) {
            JedisPool current = pool;
            if (current == null) {
                return;
            }
            try (Jedis jedis = current.getResource()) {
                JedisPubSub listener = new JedisPubSub() {
                    @Override
                    public void onMessage(String ch, String payload) {
                        receive(payload);
                    }

                    @Override
                    public void onSubscribe(String ch, int subscribedChannels) {
                        if (outageLogged) {
                            outageLogged = false;
                            plugin.getLogger().info("Spy network relay reconnected to Redis.");
                        }
                    }
                };
                subscription = listener;
                jedis.subscribe(listener, channel);
            } catch (Exception exception) {
                if (running && !outageLogged) {
                    outageLogged = true;
                    plugin.getLogger().warning("Spy network relay cannot reach Redis (" + exception.getMessage()
                            + "). Network spy is unavailable until it reconnects; retrying every 5 seconds. "
                            + "Check the redis-sync connection settings, or set private-messages.spy.network.enabled to false.");
                }
            }
            subscription = null;
            if (!running) {
                return;
            }
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException interrupted) {
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Delivery
    // -------------------------------------------------------------------------

    /** A private message was sent on THIS server. Names are the real login names. */
    public void relay(UUID senderId, UUID targetId, String sender, String target, String message, String serverName) {
        if (!isEnabled()) {
            return;
        }
        deliver(senderId, targetId, sender, target, message, serverName, false);
        JedisPool current = pool;
        if (current == null) {
            return;
        }
        String payload = serverId + "|" + encode(sender) + "|" + encode(target) + "|" + encode(message)
                + "|" + encode(serverName);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = current.getResource()) {
                jedis.publish(channel, payload);
            } catch (Exception exception) {
                plugin.getLogger().warning("Could not relay a private message to the network: " + exception.getMessage());
            }
        });
    }

    private void receive(String payload) {
        String[] parts = payload.split("\\|", 5);
        if (parts.length < 5 || parts[0].equals(serverId)) {
            return;
        }
        try {
            String sender = decode(parts[1]);
            String target = decode(parts[2]);
            String message = decode(parts[3]);
            String serverName = decode(parts[4]);
            // Back to the main thread: we are on the Redis subscriber thread here.
            Bukkit.getScheduler().runTask(plugin,
                    () -> deliver(null, null, sender, target, message, serverName.isBlank() ? parts[0] : serverName, true));
        } catch (IllegalArgumentException ignored) {
            // Not one of ours.
        }
    }

    private void deliver(UUID senderId, UUID targetId, String sender, String target, String message,
                         String serverName, boolean remote) {
        Component line = null;
        for (Player staff : Bukkit.getOnlinePlayers()) {
            UUID id = staff.getUniqueId();
            if (id.equals(senderId) || id.equals(targetId) || !staff.hasPermission(PERMISSION)) {
                continue;
            }
            Mode mode = mode(id);
            if (mode == Mode.OFF || (remote && mode != Mode.NETWORK)) {
                continue;
            }
            if (line == null) {
                line = messages.component(remote ? "spy.format-network" : "spy.format",
                        MessagePlaceholder.of("sender", sender),
                        MessagePlaceholder.of("target", target),
                        MessagePlaceholder.of("message", message),
                        MessagePlaceholder.of("server", serverName));
            }
            staff.sendMessage(line);
        }
    }

    // -------------------------------------------------------------------------
    // Persistence (staff modes survive relogs and restarts)
    // -------------------------------------------------------------------------

    private void load() {
        if (!file.exists()) {
            return;
        }
        ConfigurationSection section = YamlConfiguration.loadConfiguration(file).getConfigurationSection("modes");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                modes.put(UUID.fromString(key), Mode.parse(section.getString(key), Mode.OFF));
            } catch (IllegalArgumentException ignored) {
                // Skip a hand-edited bad entry.
            }
        }
    }

    private void save() {
        Map<UUID, Mode> snapshot = new HashMap<>(modes);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(snapshot));
    }

    private synchronized void write(Map<UUID, Mode> snapshot) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Mode> entry : snapshot.entrySet()) {
            yaml.set("modes." + entry.getKey(), entry.getValue().name().toLowerCase(Locale.ROOT));
        }
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to save spy-data.yml: " + exception.getMessage());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
