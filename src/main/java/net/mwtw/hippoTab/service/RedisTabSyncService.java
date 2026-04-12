package net.mwtw.hippoTab.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mwtw.hippoTab.config.RedisSyncConfig;
import net.mwtw.hippoTab.config.TabConfig;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RedisTabSyncService {
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();
    private static final int ORDER_BASE = 1_000_000;
    private static final int ORDER_MIN = 0;
    private static final int ORDER_MAX = 2_000_000;

    private final JavaPlugin plugin;
    private final TabConfig config;
    private final RedisSyncConfig redisConfig;
    private final TabTextFormatter formatter;
    private final PlaceholderService placeholderService;

    private final Map<UUID, Set<UUID>> remoteEntriesByViewer = new ConcurrentHashMap<>();
    private volatile List<SyncedPlayer> latestRemotePlayers = List.of();
    private volatile List<SyncedPlayer> latestLocalPlayers = List.of();
    private volatile Set<UUID> latestLocalPlayerIds = Set.of();
    private JedisPool pool;
    private BukkitTask redisTask;
    private BukkitTask tabApplyTask;

    public RedisTabSyncService(JavaPlugin plugin, TabConfig config, TabTextFormatter formatter, PlaceholderService placeholderService) {
        this.plugin = plugin;
        this.config = config;
        this.redisConfig = config.redisSync();
        this.formatter = formatter;
        this.placeholderService = placeholderService;
    }

    public void start() {
        if (!redisConfig.enabled()) {
            return;
        }

        if (!plugin.getServer().getPluginManager().isPluginEnabled("packetevents")) {
            plugin.getLogger().warning("Redis tab sync is enabled, but PacketEvents is not available. Skipping.");
            return;
        }

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(4);
        poolConfig.setMinIdle(1);

        String username = blankToNull(redisConfig.username());
        String password = blankToNull(redisConfig.password());
        pool = new JedisPool(
            poolConfig,
            redisConfig.host(),
            redisConfig.port(),
            2_000,
            2_000,
            username,
            password,
            redisConfig.database(),
            null
        );

        redisTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            this::publishAndReadNetworkState,
            1L,
            redisConfig.publishIntervalTicks()
        );

        tabApplyTask = Bukkit.getScheduler().runTaskTimer(
            plugin,
            this::applyRemoteEntriesToTab,
            10L,
            20L
        );

        plugin.getLogger().info("Redis tab sync enabled for server-id=" + redisConfig.serverId());
    }

    public void stop() {
        if (redisTask != null) {
            redisTask.cancel();
            redisTask = null;
        }
        if (tabApplyTask != null) {
            tabApplyTask.cancel();
            tabApplyTask = null;
        }

        removeAllRemoteEntriesFromViewers();
        remoteEntriesByViewer.clear();
        latestRemotePlayers = List.of();

        if (pool != null) {
            pool.close();
            pool = null;
        }
    }

    private void publishAndReadNetworkState() {
        if (pool == null) {
            return;
        }

        List<SyncedPlayer> localPlayers = latestLocalPlayers;
        try (Jedis jedis = pool.getResource()) {
            writeLocalState(jedis, localPlayers);
            latestRemotePlayers = readRemotePlayers(jedis);
        } catch (Exception exception) {
            plugin.getLogger().warning("Redis tab sync error: " + exception.getMessage());
        }
    }

    private List<SyncedPlayer> snapshotLocalPlayers() {
        List<SyncedPlayer> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            int order = resolveListOrder(player);
            Component listName = config.playerListNameFormat() != null && !config.playerListNameFormat().isBlank()
                ? formatter.toComponent(player, config.playerListNameFormat())
                : Component.text(player.getName());
            String displayName = PLAIN_TEXT.serialize(listName);
            players.add(new SyncedPlayer(player.getUniqueId(), player.getName(), displayName, order, player.getPing()));
        }
        return players;
    }

    private void writeLocalState(Jedis jedis, List<SyncedPlayer> localPlayers) {
        String serversKey = serversKey();
        String heartbeatKey = heartbeatKey(redisConfig.serverId());
        String serverPlayersKey = serverPlayersKey(redisConfig.serverId());

        jedis.sadd(serversKey, redisConfig.serverId());
        jedis.setex(heartbeatKey, redisConfig.entryTtlSeconds(), "1");
        jedis.del(serverPlayersKey);

        if (!localPlayers.isEmpty()) {
            Map<String, String> encoded = new HashMap<>();
            for (SyncedPlayer player : localPlayers) {
                encoded.put(player.uuid().toString(), serializePlayer(player));
            }
            jedis.hset(serverPlayersKey, encoded);
        }
        jedis.expire(serverPlayersKey, redisConfig.entryTtlSeconds());
    }

    private List<SyncedPlayer> readRemotePlayers(Jedis jedis) {
        Set<String> serverIds = jedis.smembers(serversKey());
        if (serverIds.isEmpty()) {
            return List.of();
        }

        Set<UUID> localPlayerIds = latestLocalPlayerIds;

        List<SyncedPlayer> remote = new ArrayList<>();
        for (String serverId : serverIds) {
            if (serverId == null || serverId.isBlank() || serverId.equals(redisConfig.serverId())) {
                continue;
            }
            if (!jedis.exists(heartbeatKey(serverId))) {
                jedis.srem(serversKey(), serverId);
                continue;
            }

            Map<String, String> players = jedis.hgetAll(serverPlayersKey(serverId));
            for (Map.Entry<String, String> entry : players.entrySet()) {
                SyncedPlayer synced = deserializePlayer(entry.getKey(), entry.getValue());
                if (synced == null || localPlayerIds.contains(synced.uuid())) {
                    continue;
                }
                remote.add(synced);
            }
        }

        return remote;
    }

    private void applyRemoteEntriesToTab() {
        List<SyncedPlayer> localSnapshot = snapshotLocalPlayers();
        latestLocalPlayers = List.copyOf(localSnapshot);
        Set<UUID> localIds = new HashSet<>();
        for (SyncedPlayer syncedPlayer : localSnapshot) {
            localIds.add(syncedPlayer.uuid());
        }
        latestLocalPlayerIds = Set.copyOf(localIds);

        List<SyncedPlayer> remotePlayers = latestRemotePlayers;
        Set<UUID> targetIds = new HashSet<>();
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> playerInfos = new ArrayList<>();

        for (SyncedPlayer remote : remotePlayers) {
            if (localIds.contains(remote.uuid())) {
                continue;
            }
            if (remote.name() == null || remote.name().isBlank()) {
                continue;
            }
            String profileName = remote.name().length() > 16 ? remote.name().substring(0, 16) : remote.name();
            WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                new UserProfile(remote.uuid(), profileName),
                true,
                Math.max(0, remote.ping()),
                GameMode.SURVIVAL,
                remote.displayName() == null || remote.displayName().isBlank() ? null : Component.text(remote.displayName()),
                null,
                clamp(remote.listOrder(), ORDER_MIN, ORDER_MAX)
            );
            playerInfos.add(info);
            targetIds.add(remote.uuid());
        }

        Collection<? extends Player> viewers = Bukkit.getOnlinePlayers();
        Set<UUID> onlineViewers = new HashSet<>();
        for (Player viewer : viewers) {
            onlineViewers.add(viewer.getUniqueId());
            Set<UUID> previousIds = remoteEntriesByViewer.computeIfAbsent(viewer.getUniqueId(), ignored -> new HashSet<>());

            Set<UUID> toRemove = new HashSet<>(previousIds);
            toRemove.removeAll(targetIds);
            if (!toRemove.isEmpty()) {
                PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, new WrapperPlayServerPlayerInfoRemove(new ArrayList<>(toRemove)));
            }

            if (!playerInfos.isEmpty()) {
                WrapperPlayServerPlayerInfoUpdate updatePacket = new WrapperPlayServerPlayerInfoUpdate(
                    EnumSet.of(
                        WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LIST_ORDER
                    ),
                    playerInfos
                );
                PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, updatePacket);
            }

            previousIds.clear();
            previousIds.addAll(targetIds);
        }

        remoteEntriesByViewer.keySet().removeIf(viewerId -> !onlineViewers.contains(viewerId));
    }

    private void removeAllRemoteEntriesFromViewers() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Set<UUID> remoteIds = remoteEntriesByViewer.get(viewer.getUniqueId());
            if (remoteIds == null || remoteIds.isEmpty()) {
                continue;
            }
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, new WrapperPlayServerPlayerInfoRemove(new ArrayList<>(remoteIds)));
        }
    }

    private int resolveListOrder(Player player) {
        if (!config.sortingEnabled()) {
            return ORDER_BASE;
        }

        int rank = config.defaultRank();
        String raw = placeholderService.apply(player, config.rankPlaceholder());
        if (raw != null) {
            String digits = raw.replaceAll("[^0-9\\-]", "");
            if (!digits.isBlank()) {
                try {
                    rank = Integer.parseInt(digits);
                } catch (NumberFormatException ignored) {
                    rank = config.defaultRank();
                }
            }
        }

        int order = config.sortingDescending() ? ORDER_BASE - rank : ORDER_BASE + rank;
        return clamp(order, ORDER_MIN, ORDER_MAX);
    }

    private String serializePlayer(SyncedPlayer player) {
        return encode(player.name()) + "|" + player.listOrder() + "|" + player.ping() + "|" + encode(player.displayName());
    }

    private SyncedPlayer deserializePlayer(String uuidRaw, String raw) {
        if (uuidRaw == null || raw == null || raw.isBlank()) {
            return null;
        }

        String[] parts = raw.split("\\|", 4);
        if (parts.length != 4) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(uuidRaw);
            String name = decode(parts[0]);
            int listOrder = Integer.parseInt(parts[1]);
            int ping = Integer.parseInt(parts[2]);
            String displayName = decode(parts[3]);
            return new SyncedPlayer(uuid, name, displayName, listOrder, ping);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String serversKey() {
        return redisConfig.keyPrefix() + ":servers";
    }

    private String serverPlayersKey(String serverId) {
        return redisConfig.keyPrefix() + ":server:" + serverId + ":players";
    }

    private String heartbeatKey(String serverId) {
        return redisConfig.keyPrefix() + ":server:" + serverId + ":heartbeat";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String encode(String value) {
        String raw = value == null ? "" : value;
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record SyncedPlayer(UUID uuid, String name, String displayName, int listOrder, int ping) {
    }
}
