package net.mwtw.hippoTab.config;

import org.bukkit.plugin.java.JavaPlugin;

public record RedisSyncConfig(
    boolean enabled,
    String host,
    int port,
    String username,
    String password,
    int database,
    String keyPrefix,
    String serverId,
    long publishIntervalTicks,
    int entryTtlSeconds,
    boolean velocityRegistryEnabled,
    String velocityOnlineSetKey,
    String velocityProfilesHashKey,
    boolean velocityRequireProfile,
    String velocityRequiredPermission,
    boolean velocityUseProfilePrefixForTeam,
    boolean velocityUseProfileWeightForSorting,
    boolean teamSyncEnabled,
    boolean ignoreTeamPacketErrors
) {
    public static RedisSyncConfig from(JavaPlugin plugin) {
        String configuredServerId = plugin.getConfig().getString("redis-sync.server-id", "");
        String defaultServerId = "server-" + plugin.getServer().getPort();
        String serverId = configuredServerId == null || configuredServerId.isBlank() ? defaultServerId : configuredServerId;

        return new RedisSyncConfig(
            plugin.getConfig().getBoolean("redis-sync.enabled", false),
            plugin.getConfig().getString("redis-sync.host", "127.0.0.1"),
            plugin.getConfig().getInt("redis-sync.port", 6379),
            plugin.getConfig().getString("redis-sync.username", ""),
            plugin.getConfig().getString("redis-sync.password", ""),
            Math.max(0, plugin.getConfig().getInt("redis-sync.database", 0)),
            plugin.getConfig().getString("redis-sync.key-prefix", "hippotab:tab-sync"),
            serverId,
            Math.max(20L, plugin.getConfig().getLong("redis-sync.publish-interval-ticks", 40L)),
            Math.max(10, plugin.getConfig().getInt("redis-sync.entry-ttl-seconds", 30)),
            plugin.getConfig().getBoolean("redis-sync.velocity-registry.enabled", false),
            plugin.getConfig().getString("redis-sync.velocity-registry.online-set-key", "hippotab:velocity:online"),
            plugin.getConfig().getString("redis-sync.velocity-registry.profiles-hash-key", "hippotab:velocity:profiles"),
            plugin.getConfig().getBoolean("redis-sync.velocity-registry.require-profile", true),
            plugin.getConfig().getString("redis-sync.velocity-registry.required-permission", ""),
            plugin.getConfig().getBoolean("redis-sync.velocity-registry.use-profile-prefix-for-team", true),
            plugin.getConfig().getBoolean("redis-sync.velocity-registry.use-profile-weight-for-sorting", true),
            plugin.getConfig().getBoolean("redis-sync.team-sync.enabled", false),
            plugin.getConfig().getBoolean("redis-sync.team-sync.ignore-packet-errors", true)
        );
    }
}
