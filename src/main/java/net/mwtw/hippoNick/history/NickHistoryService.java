package net.mwtw.hippoNick.history;

import com.zaxxer.hikari.HikariDataSource;
import net.mwtw.hippoTab.discord.DiscordLogService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Permanent record of every nick change (set, rank change, clear): who, which nick,
 * when, and who did it. Stored next to the nick profiles — the MariaDB table
 * {@code nick_history} when {@code storage.type} is mariadb (shared by every server on
 * that database), otherwise the file {@code nick-history.log} of this server. Each
 * change is also posted to the Discord nick log.
 */
public final class NickHistoryService {
    public static final String SET = "SET";
    public static final String RANK = "RANK";
    public static final String CLEAR = "CLEAR";

    /** One recorded change. */
    public record Entry(long time, UUID uuid, String realName, String action, String nickname,
                        String rank, String skin, String actor, String server) {
    }

    private final Plugin plugin;
    private final DiscordLogService discordLog;
    private final HikariDataSource dataSource;
    private final Path file;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HippoNick-history");
        thread.setDaemon(true);
        return thread;
    });

    /** @param dataSource the MariaDB pool, or null to keep the history in a local file */
    public NickHistoryService(Plugin plugin, DiscordLogService discordLog, HikariDataSource dataSource) {
        this.plugin = plugin;
        this.discordLog = discordLog;
        this.dataSource = dataSource;
        this.file = plugin.getDataFolder().toPath().resolve("nick-history.log");
        if (dataSource != null) {
            executor.execute(this::createTable);
        }
    }

    public void record(UUID uuid, String realName, String action, String nickname, String rank, String skin, String actor) {
        Entry entry = new Entry(System.currentTimeMillis(), uuid, realName, action,
                nickname, rank, skin, actor, discordLog.serverName());
        discordLog.nickChange(action, realName, nickname, rank, skin, actor);
        executor.execute(() -> {
            try {
                if (dataSource != null) {
                    insert(entry);
                } else {
                    append(entry);
                }
            } catch (Exception exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to record nick history for " + realName, exception);
            }
        });
    }

    /**
     * Looks up the changes of a player (by real name or UUID) or of a nickname (who used
     * it), newest first. The result is handed over on the main thread.
     */
    public void lookup(String query, int offset, int limit, Consumer<List<Entry>> callback) {
        executor.execute(() -> {
            List<Entry> result;
            try {
                result = dataSource != null ? select(query, offset, limit) : scan(query, offset, limit);
            } catch (Exception exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to read nick history for " + query, exception);
                result = List.of();
            }
            List<Entry> found = result;
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(found));
        });
    }

    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // File backend: one tab-separated line per change, appended
    // -------------------------------------------------------------------------

    private void append(Entry entry) throws IOException {
        Files.createDirectories(file.getParent());
        String line = String.join("\t",
                Long.toString(entry.time()), entry.uuid().toString(), clean(entry.realName()), entry.action(),
                clean(entry.nickname()), clean(entry.rank()), clean(entry.skin()), clean(entry.actor()),
                clean(entry.server())) + "\n";
        Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private List<Entry> scan(String query, int offset, int limit) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Entry> out = new ArrayList<>();
        int skipped = 0;
        for (int i = lines.size() - 1; i >= 0 && out.size() < limit; i--) {
            String[] parts = lines.get(i).split("\t", -1);
            if (parts.length < 9) {
                continue;
            }
            if (!parts[2].equalsIgnoreCase(query) && !parts[4].equalsIgnoreCase(query) && !parts[1].equalsIgnoreCase(query)) {
                continue;
            }
            if (skipped < offset) {
                skipped++;
                continue;
            }
            try {
                out.add(new Entry(Long.parseLong(parts[0]), UUID.fromString(parts[1]), parts[2], parts[3],
                        parts[4], parts[5], parts[6], parts[7], parts[8]));
            } catch (IllegalArgumentException ignored) {
                // Skip a damaged line.
            }
        }
        return out;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    // -------------------------------------------------------------------------
    // MariaDB backend
    // -------------------------------------------------------------------------

    private void createTable() {
        String schema = """
                CREATE TABLE IF NOT EXISTS nick_history (
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  changed_at BIGINT NOT NULL,
                  uuid CHAR(36) NOT NULL,
                  real_name VARCHAR(32) NOT NULL,
                  action VARCHAR(16) NOT NULL,
                  nickname VARCHAR(32) NULL,
                  rank_name VARCHAR(64) NULL,
                  skin VARCHAR(1024) NULL,
                  actor VARCHAR(64) NULL,
                  server VARCHAR(64) NULL,
                  INDEX idx_history_uuid (uuid),
                  INDEX idx_history_real_name (real_name),
                  INDEX idx_history_nickname (nickname)
                )
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(schema)) {
            statement.execute();
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create the nick_history table", exception);
        }
    }

    private void insert(Entry entry) throws SQLException {
        String sql = "INSERT INTO nick_history(changed_at, uuid, real_name, action, nickname, rank_name, skin, actor, server) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, entry.time());
            statement.setString(2, entry.uuid().toString());
            statement.setString(3, entry.realName());
            statement.setString(4, entry.action());
            statement.setString(5, entry.nickname());
            statement.setString(6, entry.rank());
            statement.setString(7, entry.skin());
            statement.setString(8, entry.actor());
            statement.setString(9, entry.server());
            statement.executeUpdate();
        }
    }

    private List<Entry> select(String query, int offset, int limit) throws SQLException {
        String sql = "SELECT changed_at, uuid, real_name, action, nickname, rank_name, skin, actor, server "
                + "FROM nick_history WHERE LOWER(real_name) = LOWER(?) OR LOWER(nickname) = LOWER(?) OR uuid = ? "
                + "ORDER BY id DESC LIMIT ? OFFSET ?";
        List<Entry> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, query);
            statement.setString(2, query);
            statement.setString(3, query);
            statement.setInt(4, limit);
            statement.setInt(5, offset);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    out.add(new Entry(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                            rs.getString(4), nullToEmpty(rs.getString(5)), nullToEmpty(rs.getString(6)),
                            nullToEmpty(rs.getString(7)), nullToEmpty(rs.getString(8)), nullToEmpty(rs.getString(9))));
                }
            }
        }
        return out;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
