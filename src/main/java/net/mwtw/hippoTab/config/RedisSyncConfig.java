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
    int entryTtlSeconds
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
            Math.max(10, plugin.getConfig().getInt("redis-sync.entry-ttl-seconds", 30))
        );
    }
}
