package net.mwtw.hippoNick.service;

import net.mwtw.hippoNick.Core;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Shares the list of currently nicked players between servers, so /realname can show
 * the whole network. Each server publishes its own nicked players to Redis on a timer
 * (the connection settings of {@code redis-sync}) and keeps a cached copy of what the
 * other servers published; nothing here blocks the main thread on Redis.
 */
public final class NetworkNickDirectory {
    /** A nicked player on another server. The texture is the skin they are shown with. */
    public record Entry(UUID uuid, String realName, String nickname, String rank, String server,
                        String textureValue, String textureSignature) {
    }

    private final Core plugin;
    private final NickManager nickManager;
    private final Supplier<String> serverName;

    private volatile List<Entry> remote = List.of();
    private volatile JedisPool pool;
    private String serverId = "";
    private String keyPrefix = "";
    private int ttlSeconds = 15;
    private BukkitTask task;
    // One warning per outage instead of one every update.
    private volatile boolean outageLogged;

    public NetworkNickDirectory(Core plugin, NickManager nickManager, Supplier<String> serverName) {
        this.plugin = plugin;
        this.nickManager = nickManager;
        this.serverName = serverName;
    }

    /** Whether this server is sharing nicked players with the network right now. */
    public boolean isActive() {
        return pool != null;
    }

    /** Nicked players on the other servers, as of the last refresh. */
    public List<Entry> remoteEntries() {
        return remote;
    }

    /** A nicked player on another server whose nickname or real name is exactly this. */
    public Optional<Entry> find(String query) {
        for (Entry entry : remote) {
            if (entry.nickname().equalsIgnoreCase(query) || entry.realName().equalsIgnoreCase(query)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    public synchronized void start() {
        if (!plugin.getConfig().getBoolean("nick.realname-network.enabled", false)) {
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
        keyPrefix = plugin.getConfig().getString("redis-sync.key-prefix", "hippotab:tab-sync") + ":nicked";
        long interval = Math.max(20L, plugin.getConfig().getLong("nick.realname-network.update-interval-ticks", 100L));
        // An entry outlives three missed updates, then the server counts as gone.
        ttlSeconds = (int) Math.max(15L, interval * 3L / 20L);

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        poolConfig.setMaxIdle(1);
        pool = new JedisPool(poolConfig, host, port, 2_000, 2_000, username, password, database, null);
        // Collect the local players on the main thread, talk to Redis off it.
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Map<String, String> local = snapshotLocal();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> sync(local));
        }, 20L, interval);
        plugin.getLogger().info("Network /realname enabled (server-id=" + serverId + ").");
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        JedisPool current = pool;
        pool = null;
        remote = List.of();
        if (current != null) {
            // Take this server's players out right away instead of waiting for the expiry.
            try (Jedis jedis = current.getResource()) {
                jedis.del(playersKey(serverId));
            } catch (Exception ignored) {
                // The entry expires on its own.
            }
            current.close();
        }
    }

    private Map<String, String> snapshotLocal() {
        Map<String, String> out = new HashMap<>();
        String server = serverName.get();
        for (Player player : nickManager.onlineNickedPlayers()) {
            var display = nickManager.resolveDisplay(player);
            String textureValue = "";
            String textureSignature = "";
            for (var property : player.getPlayerProfile().getProperties()) {
                if ("textures".equalsIgnoreCase(property.getName()) && property.getValue() != null) {
                    textureValue = property.getValue();
                    textureSignature = property.getSignature() == null ? "" : property.getSignature();
                    break;
                }
            }
            out.put(player.getUniqueId().toString(), String.join("|",
                    encode(nickManager.realName(player)), encode(display.effectiveName()),
                    encode(display.effectiveRank()), encode(server),
                    encode(textureValue), encode(textureSignature)));
        }
        return out;
    }

    private void sync(Map<String, String> local) {
        JedisPool current = pool;
        if (current == null) {
            return;
        }
        try (Jedis jedis = current.getResource()) {
            String serversKey = keyPrefix + ":servers";
            String ownKey = playersKey(serverId);
            jedis.sadd(serversKey, serverId);
            jedis.del(ownKey);
            if (!local.isEmpty()) {
                jedis.hset(ownKey, local);
                jedis.expire(ownKey, ttlSeconds);
            }

            List<Entry> entries = new ArrayList<>();
            for (String otherId : jedis.smembers(serversKey)) {
                if (otherId == null || otherId.isBlank() || otherId.equals(serverId)) {
                    continue;
                }
                for (Map.Entry<String, String> row : jedis.hgetAll(playersKey(otherId)).entrySet()) {
                    Entry entry = decodeEntry(row.getKey(), row.getValue(), otherId);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            }
            remote = List.copyOf(entries);
            if (outageLogged) {
                outageLogged = false;
                plugin.getLogger().info("Network /realname reconnected to Redis.");
            }
        } catch (Exception exception) {
            if (!outageLogged) {
                outageLogged = true;
                plugin.getLogger().warning("Network /realname cannot reach Redis (" + exception.getMessage()
                        + "). Only this server's players are listed until it reconnects. "
                        + "Check the redis-sync connection settings, or set nick.realname-network.enabled to false.");
            }
        }
    }

    private Entry decodeEntry(String uuidRaw, String raw, String fallbackServer) {
        String[] parts = raw.split("\\|", -1);
        if (parts.length < 6) {
            return null;
        }
        try {
            String server = decode(parts[3]);
            return new Entry(UUID.fromString(uuidRaw), decode(parts[0]), decode(parts[1]), decode(parts[2]),
                    server.isBlank() ? fallbackServer : server, decode(parts[4]), decode(parts[5]));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String playersKey(String id) {
        return keyPrefix + ":server:" + id;
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
