package net.mwtw.hippoNick.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.mwtw.hippoNick.Core;
import net.mwtw.hippoNick.domain.NickProfile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public final class MariaDbNickRepository implements NickRepository {
    private final Core plugin;
    private final HikariDataSource dataSource;

    public MariaDbNickRepository(Core plugin) throws SQLException {
        this.plugin = plugin;
        this.dataSource = createDataSource(plugin);
        initializeSchema();
    }

    @Override
    public Optional<NickProfile> find(UUID uuid) {
        String sql = "SELECT uuid, nickname, rank_override, skin_name, updated_at FROM nick_profiles WHERE uuid = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(map(resultSet));
            }
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to fetch profile for " + uuid, exception);
            return Optional.empty();
        }
    }

    @Override
    public Collection<NickProfile> findAll() {
        String sql = "SELECT uuid, nickname, rank_override, skin_name, updated_at FROM nick_profiles";
        ArrayList<NickProfile> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                out.add(map(resultSet));
            }
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to fetch all nick profiles from MariaDB", exception);
        }
        return out;
    }

    @Override
    public void save(NickProfile profile) {
        String sql = """
                INSERT INTO nick_profiles(uuid, nickname, rank_override, skin_name, updated_at)
                VALUES(?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    nickname = VALUES(nickname),
                    rank_override = VALUES(rank_override),
                    skin_name = VALUES(skin_name),
                    updated_at = VALUES(updated_at)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profile.uuid().toString());
            statement.setString(2, profile.nickname());
            statement.setString(3, profile.rankOverride());
            statement.setString(4, profile.skinName());
            statement.setTimestamp(5, Timestamp.from(profile.updatedAt()));
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save profile for " + profile.uuid(), exception);
        }
    }

    @Override
    public void delete(UUID uuid) {
        String sql = "DELETE FROM nick_profiles WHERE uuid = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete profile for " + uuid, exception);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private void initializeSchema() throws SQLException {
        String schema = """
                CREATE TABLE IF NOT EXISTS nick_profiles (
                  uuid CHAR(36) NOT NULL PRIMARY KEY,
                  nickname VARCHAR(32) NULL,
                  rank_override VARCHAR(64) NULL,
                  skin_name VARCHAR(1024) NULL,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  INDEX idx_rank_override (rank_override),
                  INDEX idx_updated_at (updated_at)
                )
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(schema)) {
            statement.execute();
        }
        String alter = "ALTER TABLE nick_profiles ADD COLUMN IF NOT EXISTS skin_name VARCHAR(1024) NULL";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(alter)) {
            statement.execute();
        }
        String widen = "ALTER TABLE nick_profiles MODIFY COLUMN skin_name VARCHAR(1024) NULL";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(widen)) {
            statement.execute();
        }
    }

    private HikariDataSource createDataSource(Core plugin) {
        String host = plugin.getConfig().getString("storage.mariadb.host", "127.0.0.1");
        int port = plugin.getConfig().getInt("storage.mariadb.port", 3306);
        String database = plugin.getConfig().getString("storage.mariadb.database", "hipponick");
        String username = plugin.getConfig().getString("storage.mariadb.username", "root");
        String password = plugin.getConfig().getString("storage.mariadb.password", "");
        boolean ssl = plugin.getConfig().getBoolean("storage.mariadb.ssl", false);
        int poolSize = plugin.getConfig().getInt("storage.mariadb.pool-size", 10);

        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        // MariaDB Connector/J 3.x uses sslMode (not the legacy useSsl/useSSL flag).
        config.setJdbcUrl("jdbc:mariadb://%s:%d/%s?sslMode=%s"
                .formatted(host, port, database, ssl ? "trust" : "disable"));
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(Math.min(2, poolSize));
        config.setPoolName("HippoNickPool");
        config.setConnectionTimeout(10_000L);
        config.setValidationTimeout(5_000L);
        return new HikariDataSource(config);
    }

    private NickProfile map(ResultSet resultSet) throws SQLException {
        UUID uuid = UUID.fromString(resultSet.getString("uuid"));
        String nickname = resultSet.getString("nickname");
        String rankOverride = resultSet.getString("rank_override");
        String skinName = resultSet.getString("skin_name");
        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        Instant instant = updatedAt == null ? Instant.now() : updatedAt.toInstant();
        return new NickProfile(uuid, nickname, rankOverride, skinName, instant);
    }
}
