package net.mwtw.hippoTab.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.mwtw.hippoTab.config.RedisSyncConfig;
import net.mwtw.hippoTab.config.TabConfig;
import net.mwtw.hippoTab.text.TabTextFormatter;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.OfflinePlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static net.mwtw.hippoTab.Core.ERROR_TRACKER;

public final class RedisTabSyncService {
    private static final GsonComponentSerializer GSON_COMPONENT = GsonComponentSerializer.gson();
    private static final String HIPPO_REMOTE_TEAM_PREFIX = "htr_";
    private static final long REMOTE_REMOVE_GRACE_MILLIS = 5_000L;
    private static final int ORDER_BASE = 1_000_000;
    private static final int ORDER_MIN = 0;
    private static final int ORDER_MAX = 2_000_000;

    private final JavaPlugin plugin;
    private final TabConfig config;
    private final RedisSyncConfig redisConfig;
    private final TabTextFormatter formatter;
    private final PlaceholderService placeholderService;

    private final Map<UUID, Set<UUID>> remoteEntriesByViewer = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastRemoteProfileHashes = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastRemoteInfoHashes = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastRemoteTeamHashes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSeenRemoteAt = new ConcurrentHashMap<>();
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

        tabApplyTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            this::applyRemoteEntriesToTab,
            10L,
            20L
        );

        cacheLocalSnapshot(snapshotLocalPlayers());

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
        lastRemoteProfileHashes.clear();
        lastRemoteInfoHashes.clear();
        lastRemoteTeamHashes.clear();
        lastSeenRemoteAt.clear();
        latestRemotePlayers = List.of();
        latestLocalPlayers = List.of();
        latestLocalPlayerIds = Set.of();

        if (pool != null) {
            pool.close();
            pool = null;
        }
    }

    public void handleConnectionChange() {
        if (pool == null) {
            return;
        }
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::publishAndReadNetworkState, 1L);
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::applyRemoteEntriesToTab, 2L);
    }

    private void publishAndReadNetworkState() {
        if (pool == null) {
            return;
        }

        List<SyncedPlayer> localPlayers = snapshotLocalPlayersForPublish();
        try (Jedis jedis = pool.getResource()) {
            writeLocalState(jedis, localPlayers);
            latestRemotePlayers = readRemotePlayers(jedis);
        } catch (Exception exception) {
            plugin.getLogger().warning("Redis tab sync error: " + exception.getMessage());
            ERROR_TRACKER.trackError(exception);
        }
    }

    private List<SyncedPlayer> snapshotLocalPlayersForPublish() {
        try {
            if (Bukkit.isPrimaryThread()) {
                List<SyncedPlayer> snapshot = List.copyOf(snapshotLocalPlayers());
                cacheLocalSnapshot(snapshot);
                return snapshot;
            }
            List<SyncedPlayer> snapshot = List.copyOf(Bukkit.getScheduler().callSyncMethod(plugin, this::snapshotLocalPlayers).get(1500, TimeUnit.MILLISECONDS));
            cacheLocalSnapshot(snapshot);
            return snapshot;
        } catch (Exception exception) {
            plugin.getLogger().fine("Using cached local players for Redis publish due to snapshot timing: " + exception.getClass().getSimpleName());
            return latestLocalPlayers;
        }
    }

    private List<SyncedPlayer> snapshotLocalPlayers() {
        List<SyncedPlayer> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (shouldHideFromTab(player)) {
                continue;
            }
            int order = resolveListOrder(player);
            Component listName = config.playerListNameFormat() != null && !config.playerListNameFormat().isBlank()
                ? formatter.toComponent(player, config.playerListNameFormat())
                : Component.text(player.getName());
            String displayName = GSON_COMPONENT.serialize(listName);
            Team team = resolveMainScoreboardTeam(player);
            Component teamPrefix = team == null || team.prefix() == null ? Component.empty() : team.prefix();
            Component teamSuffix = team == null || team.suffix() == null ? Component.empty() : team.suffix();
            NamedTextColor teamColor = team == null || team.color() == null
                ? NamedTextColor.WHITE
                : NamedTextColor.nearestTo(team.color());
            players.add(new SyncedPlayer(
                player.getUniqueId(),
                player.getName(),
                displayName,
                order,
                player.getPing(),
                copyTextureProperties(player.getPlayerProfile().getProperties()),
                GSON_COMPONENT.serialize(teamPrefix),
                GSON_COMPONENT.serialize(teamSuffix),
                teamColor.value()
            ));
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
        VelocityRegistrySnapshot velocitySnapshot = readVelocityRegistrySnapshot(jedis);

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
                synced = applyVelocityRegistryRules(synced, velocitySnapshot);
                if (synced == null) {
                    continue;
                }
                remote.add(synced);
            }
        }

        return remote;
    }

    private void applyRemoteEntriesToTab() {
        List<SyncedPlayer> localSnapshot = latestLocalPlayers;
        latestLocalPlayers = List.copyOf(localSnapshot);
        Set<UUID> localIds = latestLocalPlayerIds;

        List<SyncedPlayer> remotePlayers = latestRemotePlayers;
        Set<UUID> targetIds = new HashSet<>();
        Map<UUID, SyncedPlayer> remoteById = new HashMap<>();
        Set<UUID> profileRefreshCandidates = new HashSet<>();
        Set<UUID> changedInfoIds = new HashSet<>();
        Set<UUID> changedTeamIds = new HashSet<>();
        Map<UUID, String> currentProfileHashes = new HashMap<>();
        Map<UUID, String> currentInfoHashes = new HashMap<>();
        Map<UUID, String> currentTeamHashes = new HashMap<>();
        Map<UUID, WrapperPlayServerPlayerInfoUpdate.PlayerInfo> playerInfosById = new HashMap<>();

        for (SyncedPlayer remote : remotePlayers) {
            if (localIds.contains(remote.uuid())) {
                continue;
            }
            if (remote.name() == null || remote.name().isBlank()) {
                continue;
            }
            remoteById.put(remote.uuid(), remote);
            String currentHash = profileHash(remote);
            currentProfileHashes.put(remote.uuid(), currentHash);
            String previousHash = lastRemoteProfileHashes.get(remote.uuid());
            if (previousHash != null && !previousHash.equals(currentHash)) {
                profileRefreshCandidates.add(remote.uuid());
            }
            String currentInfoHash = infoHash(remote);
            currentInfoHashes.put(remote.uuid(), currentInfoHash);
            if (!currentInfoHash.equals(lastRemoteInfoHashes.get(remote.uuid()))) {
                changedInfoIds.add(remote.uuid());
            }
            String currentTeamHash = teamHash(remote);
            currentTeamHashes.put(remote.uuid(), currentTeamHash);
            if (!currentTeamHash.equals(lastRemoteTeamHashes.get(remote.uuid()))) {
                changedTeamIds.add(remote.uuid());
            }
            String profileName = remote.name().length() > 16 ? remote.name().substring(0, 16) : remote.name();
            List<TextureProperty> textureProperties = resolveTextureProperties(remote);
            WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                new UserProfile(remote.uuid(), profileName, textureProperties),
                true,
                Math.max(0, remote.ping()),
                GameMode.SURVIVAL,
                deserializeDisplayName(remote.displayName()),
                null,
                clamp(remote.listOrder(), ORDER_MIN, ORDER_MAX)
            );
            playerInfosById.put(remote.uuid(), info);
            targetIds.add(remote.uuid());
        }
        long now = System.currentTimeMillis();
        for (UUID targetId : targetIds) {
            lastSeenRemoteAt.put(targetId, now);
        }
        lastRemoteProfileHashes.keySet().removeIf(uuid -> !currentProfileHashes.containsKey(uuid));
        lastRemoteProfileHashes.putAll(currentProfileHashes);
        lastRemoteInfoHashes.keySet().removeIf(uuid -> !currentInfoHashes.containsKey(uuid));
        lastRemoteInfoHashes.putAll(currentInfoHashes);
        lastRemoteTeamHashes.keySet().removeIf(uuid -> !currentTeamHashes.containsKey(uuid));
        lastRemoteTeamHashes.putAll(currentTeamHashes);

        Collection<? extends Player> viewers = Bukkit.getOnlinePlayers();
        Set<UUID> onlineViewers = new HashSet<>();
        Set<UUID> trackedByAnyViewer = new HashSet<>();
        for (Player viewer : viewers) {
            onlineViewers.add(viewer.getUniqueId());
            Set<UUID> previousIds = remoteEntriesByViewer.computeIfAbsent(viewer.getUniqueId(), ignored -> new HashSet<>());
            Set<UUID> profileRefreshIds = new HashSet<>(profileRefreshCandidates);
            profileRefreshIds.retainAll(previousIds);

            Set<UUID> missingIds = new HashSet<>(previousIds);
            missingIds.removeAll(targetIds);

            Set<UUID> protectedMissingIds = new HashSet<>();
            Set<UUID> staleMissingIds = new HashSet<>();
            for (UUID missingId : missingIds) {
                long lastSeenAt = lastSeenRemoteAt.getOrDefault(missingId, 0L);
                if (lastSeenAt > 0L && now - lastSeenAt < REMOTE_REMOVE_GRACE_MILLIS) {
                    protectedMissingIds.add(missingId);
                } else {
                    staleMissingIds.add(missingId);
                }
            }

            Set<UUID> toRemove = new HashSet<>(staleMissingIds);
            toRemove.addAll(profileRefreshIds);
            if (!toRemove.isEmpty()) {
                PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, new WrapperPlayServerPlayerInfoRemove(new ArrayList<>(toRemove)));
                if (redisConfig.teamSyncEnabled()) {
                    removeRemoteTeams(viewer, toRemove);
                }
            }

            List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> playerInfos = new ArrayList<>();
            for (UUID remoteId : targetIds) {
                if (!previousIds.contains(remoteId) || changedInfoIds.contains(remoteId)) {
                    WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = playerInfosById.get(remoteId);
                    if (info != null) {
                        playerInfos.add(info);
                    }
                }
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
            if (redisConfig.teamSyncEnabled()) {
                for (UUID remoteId : targetIds) {
                    SyncedPlayer remote = remoteById.get(remoteId);
                    if (remote == null) {
                        continue;
                    }
                    boolean isNewEntry = !previousIds.contains(remoteId);
                    boolean shouldCreate = isNewEntry || profileRefreshIds.contains(remoteId);
                    if (shouldCreate || changedTeamIds.contains(remoteId)) {
                        syncRemoteTeam(viewer, remote, shouldCreate);
                    }
                }
            }

            previousIds.clear();
            previousIds.addAll(targetIds);
            previousIds.addAll(protectedMissingIds);
            trackedByAnyViewer.addAll(previousIds);
        }

        remoteEntriesByViewer.keySet().removeIf(viewerId -> !onlineViewers.contains(viewerId));
        lastSeenRemoteAt.keySet().removeIf(uuid -> !targetIds.contains(uuid) && !trackedByAnyViewer.contains(uuid));
    }

    private void cacheLocalSnapshot(List<SyncedPlayer> snapshot) {
        latestLocalPlayers = List.copyOf(snapshot);
        Set<UUID> localIds = new HashSet<>();
        for (SyncedPlayer syncedPlayer : snapshot) {
            localIds.add(syncedPlayer.uuid());
        }
        latestLocalPlayerIds = Set.copyOf(localIds);
    }

    private void removeAllRemoteEntriesFromViewers() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Set<UUID> remoteIds = remoteEntriesByViewer.get(viewer.getUniqueId());
            if (remoteIds == null || remoteIds.isEmpty()) {
                continue;
            }
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, new WrapperPlayServerPlayerInfoRemove(new ArrayList<>(remoteIds)));
            if (redisConfig.teamSyncEnabled()) {
                removeRemoteTeams(viewer, remoteIds);
            }
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

    private boolean shouldHideFromTab(Player player) {
        String expression = config.tabHidePlayerIf();
        if (expression == null || expression.isBlank()) {
            return false;
        }
        String resolved = placeholderService.apply(player, expression);
        return isTruthy(resolved);
    }

    private boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase()) {
            case "true", "1", "yes", "y", "on" -> true;
            default -> false;
        };
    }

    private String serializePlayer(SyncedPlayer player) {
        return encode(player.name())
            + "|" + player.listOrder()
            + "|" + player.ping()
            + "|" + encode(player.displayName())
            + "|" + encode(serializeTextureProperties(player.textureProperties()))
            + "|" + encode(player.teamPrefix())
            + "|" + encode(player.teamSuffix())
            + "|" + encode(Integer.toString(player.teamColorValue()));
    }

    private SyncedPlayer deserializePlayer(String uuidRaw, String raw) {
        if (uuidRaw == null || raw == null || raw.isBlank()) {
            return null;
        }

        String[] parts = raw.split("\\|", 8);
        if (parts.length < 4) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(uuidRaw);
            String name = decode(parts[0]);
            int listOrder = Integer.parseInt(parts[1]);
            int ping = Integer.parseInt(parts[2]);
            String displayName = decode(parts[3]);
            List<TexturePropertyData> textureProperties = parts.length >= 5
                ? deserializeTextureProperties(decode(parts[4]))
                : List.of();
            String teamPrefix = parts.length >= 6 ? decode(parts[5]) : "";
            String teamSuffix = parts.length >= 7 ? decode(parts[6]) : "";
            int teamColorValue = parts.length >= 8 ? parseColorValue(decode(parts[7])) : NamedTextColor.WHITE.value();
            return new SyncedPlayer(uuid, name, displayName, listOrder, ping, textureProperties, teamPrefix, teamSuffix, teamColorValue);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Component deserializeDisplayName(String rawDisplayName) {
        if (rawDisplayName == null || rawDisplayName.isBlank()) {
            return null;
        }
        try {
            return GSON_COMPONENT.deserialize(rawDisplayName);
        } catch (Exception ignored) {
            return Component.text(rawDisplayName);
        }
    }

    private List<TextureProperty> resolveTextureProperties(SyncedPlayer remote) {
        List<TextureProperty> fromPayload = toPacketTextureProperties(remote.textureProperties());
        if (!fromPayload.isEmpty()) {
            return fromPayload;
        }

        List<TexturePropertyData> offlineProfileProperties = new ArrayList<>();
        appendOfflineProfileProperties(offlineProfileProperties, Bukkit.getOfflinePlayer(remote.uuid()));
        if (offlineProfileProperties.isEmpty() && remote.name() != null && !remote.name().isBlank()) {
            appendOfflineProfileProperties(offlineProfileProperties, Bukkit.getOfflinePlayer(remote.name()));
        }
        return toPacketTextureProperties(offlineProfileProperties);
    }

    private void appendOfflineProfileProperties(List<TexturePropertyData> out, OfflinePlayer offlinePlayer) {
        if (offlinePlayer == null || offlinePlayer.getPlayerProfile() == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (TexturePropertyData existing : out) {
            seen.add(texturePropertyKey(existing.name(), existing.value(), existing.signature()));
        }
        for (ProfileProperty property : offlinePlayer.getPlayerProfile().getProperties()) {
            String name = property.getName();
            String value = property.getValue();
            if (name == null || name.isBlank() || value == null || value.isBlank()) {
                continue;
            }
            String signature = property.getSignature();
            String key = texturePropertyKey(name, value, signature);
            if (seen.add(key)) {
                out.add(new TexturePropertyData(name, value, signature));
            }
        }
    }

    private List<TexturePropertyData> copyTextureProperties(Collection<ProfileProperty> properties) {
        if (properties == null || properties.isEmpty()) {
            return List.of();
        }
        List<TexturePropertyData> copied = new ArrayList<>();
        for (ProfileProperty property : properties) {
            if (property.getName() == null || property.getName().isBlank()
                || property.getValue() == null || property.getValue().isBlank()) {
                continue;
            }
            copied.add(new TexturePropertyData(property.getName(), property.getValue(), property.getSignature()));
        }
        return copied.isEmpty() ? List.of() : List.copyOf(copied);
    }

    private List<TextureProperty> toPacketTextureProperties(List<TexturePropertyData> properties) {
        if (properties == null || properties.isEmpty()) {
            return List.of();
        }
        List<TextureProperty> converted = new ArrayList<>();
        for (TexturePropertyData property : properties) {
            if (property.name() == null || property.name().isBlank()
                || property.value() == null || property.value().isBlank()) {
                continue;
            }
            converted.add(new TextureProperty(property.name(), property.value(), blankToNull(property.signature())));
        }
        return converted.isEmpty() ? List.of() : List.copyOf(converted);
    }

    private String serializeTextureProperties(List<TexturePropertyData> properties) {
        if (properties == null || properties.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (TexturePropertyData property : properties) {
            if (property.name() == null || property.name().isBlank()
                || property.value() == null || property.value().isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(';');
            }
            builder.append(encode(property.name()))
                .append(',')
                .append(encode(property.value()))
                .append(',')
                .append(encode(property.signature() == null ? "" : property.signature()));
        }
        return builder.toString();
    }

    private List<TexturePropertyData> deserializeTextureProperties(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<TexturePropertyData> properties = new ArrayList<>();
        String[] encodedProperties = raw.split(";");
        for (String encodedProperty : encodedProperties) {
            if (encodedProperty == null || encodedProperty.isBlank()) {
                continue;
            }
            String[] encodedParts = encodedProperty.split(",", 3);
            if (encodedParts.length != 3) {
                continue;
            }
            String name = decode(encodedParts[0]);
            String value = decode(encodedParts[1]);
            String signature = decode(encodedParts[2]);
            if (name.isBlank() || value.isBlank()) {
                continue;
            }
            properties.add(new TexturePropertyData(name, value, signature.isBlank() ? null : signature));
        }
        return properties.isEmpty() ? List.of() : List.copyOf(properties);
    }

    private String texturePropertyKey(String name, String value, String signature) {
        return name + '\u0000' + value + '\u0000' + (signature == null ? "" : signature);
    }

    private VelocityRegistrySnapshot readVelocityRegistrySnapshot(Jedis jedis) {
        if (!redisConfig.velocityRegistryEnabled()) {
            return null;
        }

        Set<UUID> onlineIds = new HashSet<>();
        String onlineSetKey = blankToNull(redisConfig.velocityOnlineSetKey());
        if (onlineSetKey != null) {
            for (String rawUuid : jedis.smembers(onlineSetKey)) {
                UUID uuid = parseUuid(rawUuid);
                if (uuid != null) {
                    onlineIds.add(uuid);
                }
            }
        }

        Map<UUID, VelocityProfileData> profilesByUuid = new HashMap<>();
        String profilesHashKey = blankToNull(redisConfig.velocityProfilesHashKey());
        if (profilesHashKey != null) {
            Map<String, String> rawProfiles = jedis.hgetAll(profilesHashKey);
            for (Map.Entry<String, String> entry : rawProfiles.entrySet()) {
                UUID uuid = parseUuid(entry.getKey());
                if (uuid == null) {
                    continue;
                }
                VelocityProfileData profile = deserializeVelocityProfile(entry.getValue());
                if (profile == null) {
                    continue;
                }
                profilesByUuid.put(uuid, profile);
            }
        }

        return new VelocityRegistrySnapshot(Set.copyOf(onlineIds), Map.copyOf(profilesByUuid));
    }

    private SyncedPlayer applyVelocityRegistryRules(SyncedPlayer synced, VelocityRegistrySnapshot snapshot) {
        if (snapshot == null) {
            return synced;
        }

        if (!snapshot.onlineIds().contains(synced.uuid())) {
            return null;
        }

        VelocityProfileData profile = snapshot.profilesByUuid().get(synced.uuid());
        if (redisConfig.velocityRequireProfile() && profile == null) {
            return null;
        }

        String requiredPermission = blankToNull(redisConfig.velocityRequiredPermission());
        if (requiredPermission != null) {
            if (profile == null || !profile.permissions().contains(requiredPermission.toLowerCase())) {
                return null;
            }
        }

        if (profile == null) {
            return synced;
        }

        String name = synced.name();
        if (profile.name() != null && !profile.name().isBlank()) {
            name = profile.name();
        }

        String teamPrefix = synced.teamPrefix();
        if (redisConfig.velocityUseProfilePrefixForTeam()
            && profile.prefixMiniMessage() != null
            && !profile.prefixMiniMessage().isBlank()) {
            teamPrefix = GSON_COMPONENT.serialize(formatter.fromMiniMessage(profile.prefixMiniMessage()));
        }

        int listOrder = synced.listOrder();
        if (redisConfig.velocityUseProfileWeightForSorting() && profile.weight() != null) {
            listOrder = resolveListOrder(profile.weight());
        }

        return new SyncedPlayer(
            synced.uuid(),
            name,
            synced.displayName(),
            listOrder,
            synced.ping(),
            synced.textureProperties(),
            teamPrefix,
            synced.teamSuffix(),
            synced.teamColorValue()
        );
    }

    private VelocityProfileData deserializeVelocityProfile(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split("\\|", 4);
        if (parts.length < 4) {
            return null;
        }

        String name = decodeLenient(parts[0]);
        String prefix = decodeLenient(parts[1]);
        Integer weight = parseOptionalInt(decodeLenient(parts[2]));
        Set<String> permissions = parsePermissions(decodeLenient(parts[3]));
        return new VelocityProfileData(name, prefix, weight, permissions);
    }

    private Set<String> parsePermissions(String rawPermissions) {
        if (rawPermissions == null || rawPermissions.isBlank()) {
            return Set.of();
        }
        Set<String> parsed = new LinkedHashSet<>();
        String[] split = rawPermissions.split(",");
        for (String permission : split) {
            if (permission == null) {
                continue;
            }
            String cleaned = permission.trim().toLowerCase();
            if (!cleaned.isBlank()) {
                parsed.add(cleaned);
            }
        }
        return parsed.isEmpty() ? Set.of() : Set.copyOf(parsed);
    }

    private Integer parseOptionalInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Team resolveMainScoreboardTeam(Player player) {
        if (Bukkit.getScoreboardManager() == null) {
            return null;
        }
        Scoreboard mainScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        if (mainScoreboard == null) {
            return null;
        }
        return mainScoreboard.getEntityTeam(player);
    }

    private void removeRemoteTeams(Player viewer, Collection<UUID> remoteIds) {
        for (UUID remoteId : remoteIds) {
            if (remoteId == null) {
                continue;
            }
            WrapperPlayServerTeams removePacket = new WrapperPlayServerTeams(
                remoteTeamName(remoteId),
                WrapperPlayServerTeams.TeamMode.REMOVE,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null,
                List.of()
            );
            sendTeamPacketSilently(viewer, removePacket);
        }
    }

    private void syncRemoteTeam(Player viewer, SyncedPlayer remote, boolean create) {
        WrapperPlayServerTeams.ScoreBoardTeamInfo info = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
            Component.text(remote.name()),
            deserializeComponentOrEmpty(remote.teamPrefix()),
            deserializeComponentOrEmpty(remote.teamSuffix()),
            WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
            WrapperPlayServerTeams.CollisionRule.ALWAYS,
            parseNamedColor(remote.teamColorValue()),
            WrapperPlayServerTeams.OptionData.NONE
        );
        WrapperPlayServerTeams packet = new WrapperPlayServerTeams(
            remoteTeamName(remote.uuid()),
            create ? WrapperPlayServerTeams.TeamMode.CREATE : WrapperPlayServerTeams.TeamMode.UPDATE,
            info,
            create ? List.of(remote.name()) : List.of()
        );
        sendTeamPacketSilently(viewer, packet);
    }

    private void sendTeamPacketSilently(Player viewer, WrapperPlayServerTeams packet) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, packet);
        } catch (RuntimeException exception) {
            if (!redisConfig.ignoreTeamPacketErrors()) {
                throw exception;
            }
            plugin.getLogger().fine("Ignored team packet sync error for viewer " + viewer.getName() + ": " + exception.getClass().getSimpleName());
        }
    }

    private String remoteTeamName(UUID uuid) {
        return HIPPO_REMOTE_TEAM_PREFIX + uuid.toString().substring(0, 12);
    }

    private Component deserializeComponentOrEmpty(String raw) {
        if (raw == null || raw.isBlank()) {
            return Component.empty();
        }
        try {
            return GSON_COMPONENT.deserialize(raw);
        } catch (Exception ignored) {
            return Component.empty();
        }
    }

    private NamedTextColor parseNamedColor(int rgbValue) {
        NamedTextColor exact = NamedTextColor.ofExact(rgbValue);
        return exact == null ? NamedTextColor.namedColor(rgbValue) : exact;
    }

    private int parseColorValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return NamedTextColor.WHITE.value();
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return NamedTextColor.WHITE.value();
        }
    }

    private String profileHash(SyncedPlayer player) {
        return player.name() + "|" + serializeTextureProperties(player.textureProperties());
    }

    private String infoHash(SyncedPlayer player) {
        return player.uuid()
            + "|" + player.name()
            + "|" + player.displayName()
            + "|" + player.listOrder()
            + "|" + player.ping()
            + "|" + serializeTextureProperties(player.textureProperties());
    }

    private String teamHash(SyncedPlayer player) {
        return player.uuid()
            + "|" + player.name()
            + "|" + player.teamPrefix()
            + "|" + player.teamSuffix()
            + "|" + player.teamColorValue();
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

    private String decodeLenient(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return decode(value);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int resolveListOrder(int rank) {
        int order = config.sortingDescending() ? ORDER_BASE - rank : ORDER_BASE + rank;
        return clamp(order, ORDER_MIN, ORDER_MAX);
    }

    private record SyncedPlayer(
        UUID uuid,
        String name,
        String displayName,
        int listOrder,
        int ping,
        List<TexturePropertyData> textureProperties,
        String teamPrefix,
        String teamSuffix,
        int teamColorValue
    ) {
    }

    private record TexturePropertyData(String name, String value, String signature) {
    }

    private record VelocityRegistrySnapshot(
        Set<UUID> onlineIds,
        Map<UUID, VelocityProfileData> profilesByUuid
    ) {
    }

    private record VelocityProfileData(
        String name,
        String prefixMiniMessage,
        Integer weight,
        Set<String> permissions
    ) {
    }
}
