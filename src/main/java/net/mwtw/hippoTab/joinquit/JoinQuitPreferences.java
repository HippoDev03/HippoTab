package net.mwtw.hippoTab.joinquit;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed per-player join/quit message preferences. Both join and quit default
 * to enabled; only players who have toggled a message differ from the default are
 * stored. Kept fully in memory so the join/quit event handlers read it synchronously
 * on the main thread; writes are flushed to disk asynchronously.
 */
public final class JoinQuitPreferences {
    private final Plugin plugin;
    private final File file;
    private final Map<UUID, Boolean> joinEnabled = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> quitEnabled = new ConcurrentHashMap<>();

    public JoinQuitPreferences(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "joinquit-data.yml");
        load();
    }

    public void load() {
        joinEnabled.clear();
        quitEnabled.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            ConfigurationSection section = players.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            if (section.contains("join")) {
                joinEnabled.put(uuid, section.getBoolean("join"));
            }
            if (section.contains("quit")) {
                quitEnabled.put(uuid, section.getBoolean("quit"));
            }
        }
    }

    public boolean isJoinEnabled(UUID uuid) {
        return joinEnabled.getOrDefault(uuid, Boolean.TRUE);
    }

    public boolean isQuitEnabled(UUID uuid) {
        return quitEnabled.getOrDefault(uuid, Boolean.TRUE);
    }

    /** Flips the player's join preference and returns the new value. */
    public boolean toggleJoin(UUID uuid) {
        boolean next = !isJoinEnabled(uuid);
        joinEnabled.put(uuid, next);
        saveAsync();
        return next;
    }

    /** Flips the player's quit preference and returns the new value. */
    public boolean toggleQuit(UUID uuid) {
        boolean next = !isQuitEnabled(uuid);
        quitEnabled.put(uuid, next);
        saveAsync();
        return next;
    }

    /**
     * Flips join and quit together as a single switch and returns the new shared value.
     * Turns both OFF only when both are currently ON; any other state (mixed or both off)
     * normalizes to both ON.
     */
    public boolean toggleBoth(UUID uuid) {
        boolean next = !(isJoinEnabled(uuid) && isQuitEnabled(uuid));
        joinEnabled.put(uuid, next);
        quitEnabled.put(uuid, next);
        saveAsync();
        return next;
    }

    private void saveAsync() {
        Map<UUID, Boolean> joinSnapshot = new HashMap<>(joinEnabled);
        Map<UUID, Boolean> quitSnapshot = new HashMap<>(quitEnabled);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeToDisk(joinSnapshot, quitSnapshot));
    }

    private synchronized void writeToDisk(Map<UUID, Boolean> joinSnapshot, Map<UUID, Boolean> quitSnapshot) {
        YamlConfiguration yaml = new YamlConfiguration();
        Set<UUID> uuids = new HashSet<>();
        uuids.addAll(joinSnapshot.keySet());
        uuids.addAll(quitSnapshot.keySet());
        for (UUID uuid : uuids) {
            String base = "players." + uuid;
            yaml.set(base + ".join", joinSnapshot.getOrDefault(uuid, Boolean.TRUE));
            yaml.set(base + ".quit", quitSnapshot.getOrDefault(uuid, Boolean.TRUE));
        }
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to save joinquit-data.yml: " + exception.getMessage());
        }
    }
}
