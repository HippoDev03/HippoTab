package net.mwtw.hippoNick.storage;

import net.mwtw.hippoNick.domain.NickProfile;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FileNickRepository implements NickRepository {
    private final Path filePath;
    private final Map<UUID, NickProfile> cache = new ConcurrentHashMap<>();

    public FileNickRepository(Path filePath) {
        this.filePath = filePath;
        load();
    }

    @Override
    public Optional<NickProfile> find(UUID uuid) {
        return Optional.ofNullable(cache.get(uuid));
    }

    @Override
    public Collection<NickProfile> findAll() {
        return new ArrayList<>(cache.values());
    }

    @Override
    public synchronized void save(NickProfile profile) {
        cache.put(profile.uuid(), profile);
        flush();
    }

    @Override
    public synchronized void delete(UUID uuid) {
        cache.remove(uuid);
        flush();
    }

    @Override
    public synchronized void close() {
        flush();
    }

    private void load() {
        try {
            Files.createDirectories(filePath.getParent());
            if (!Files.exists(filePath)) {
                return;
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(filePath.toFile());
            ConfigurationSection players = yaml.getConfigurationSection("players");
            if (players == null) {
                return;
            }
            for (String key : players.getKeys(false)) {
                UUID uuid = parseUuid(key);
                if (uuid == null) {
                    continue;
                }
                String base = "players." + key + ".";
                String nickname = yaml.getString(base + "nickname");
                String rankOverride = yaml.getString(base + "rankOverride");
                String skinName = yaml.getString(base + "skinName");
                long updatedAt = yaml.getLong(base + "updatedAt", System.currentTimeMillis());
                cache.put(uuid, new NickProfile(uuid, nickname, rankOverride, skinName, Instant.ofEpochMilli(updatedAt)));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read nick profile file: " + filePath, exception);
        }
    }

    private void flush() {
        try {
            Files.createDirectories(filePath.getParent());
            YamlConfiguration yaml = new YamlConfiguration();
            for (NickProfile profile : cache.values()) {
                String root = "players." + profile.uuid() + ".";
                yaml.set(root + "nickname", profile.nickname());
                yaml.set(root + "rankOverride", profile.rankOverride());
                yaml.set(root + "skinName", profile.skinName());
                yaml.set(root + "updatedAt", profile.updatedAt().toEpochMilli());
            }

            Path temp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            File tempFile = temp.toFile();
            yaml.save(tempFile);
            Files.move(temp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to persist nick profile file: " + filePath, exception);
        }
    }

    private UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
