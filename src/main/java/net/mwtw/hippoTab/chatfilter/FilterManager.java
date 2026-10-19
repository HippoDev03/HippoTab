package net.mwtw.hippoTab.chatfilter;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Settings and word list of the chat filter (the former HippoChatFilter plugin), read
 * from the {@code chat-filter} section of config.yml plus the custom word file.
 */
public final class FilterManager {
    private static final String SECTION = "chat-filter";
    private static final String CUSTOM_FILE = "chat-filter-words.yml";
    private static final String CACHE_DIR = "chat-filter-cache";

    private final JavaPlugin plugin;
    private final AtomicInteger loadGeneration = new AtomicInteger();
    private File customFile;
    private FileConfiguration customConfig = new YamlConfiguration();

    // Words from the configured sources (downloads/files), kept so that adding or
    // removing a custom word does not download every list again.
    private volatile Set<String> sourceWords = Set.of();
    private volatile WordMatcher matcher = new WordMatcher(List.of(), List.of(), true);
    private volatile boolean enabled = true;
    private volatile boolean ignoreSeparators = true;
    private volatile String serverName = "";
    private volatile long discordCooldownMillis = 10_000L;
    private volatile boolean blockWebUrls;
    private volatile String webUrlBypassPermission = "hippotab.chatfilter.bypass.weburls";
    private volatile boolean blockIp;
    private volatile boolean ipDetectObfuscated = true;
    private volatile String ipBypassPermission = "hippotab.chatfilter.bypass.ip";
    private volatile String discordWebhookUrl = "";
    private volatile List<Pattern> ipTargets = List.of();
    private volatile boolean blockDomain;
    private volatile boolean domainDetectObfuscated = true;
    private volatile String domainBypassPermission = "hippotab.chatfilter.bypass.domain";
    private volatile String domainDiscordWebhookUrl = "";
    private volatile Set<String> domainTlds = Set.of();
    private volatile SpamGuard.Settings spamSettings =
            new SpamGuard.Settings(false, 4, 3_000L, false, 2, 30_000L, 5, 0.8, new long[0], 600_000L);
    private volatile String repeatBypassPermission = "hippotab.chatfilter.bypass.repeat";
    private volatile String duplicateBypassPermission = "hippotab.chatfilter.bypass.repeat";
    private volatile Set<String> spamCommands = Set.of();

    public FilterManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Re-reads the settings and word lists. The caller is responsible for reloadConfig().
     * Custom words apply immediately; the configured sources are (down)loaded off the
     * main thread and join the list once they arrive.
     */
    public synchronized void reload() {
        ConfigurationSection config = plugin.getConfig().getConfigurationSection(SECTION);
        if (config == null) {
            config = new YamlConfiguration();
        }
        enabled = config.getBoolean("enabled", true);
        ignoreSeparators = config.getBoolean("ignore-separators", true);
        // The top-level server-name is the one to set; the old key inside this section still works.
        String topLevelName = plugin.getConfig().getString("server-name", "");
        serverName = topLevelName != null && !topLevelName.isBlank()
                ? topLevelName : config.getString("server-name", "");
        discordCooldownMillis = Math.max(0L, config.getLong("discord-cooldown-seconds", 10L)) * 1000L;
        blockWebUrls = config.getBoolean("block-web-urls.enabled", false);
        webUrlBypassPermission = config.getString("block-web-urls.bypass-permission", "hippotab.chatfilter.bypass.weburls");
        blockIp = config.getBoolean("block-ip.enabled", false);
        ipDetectObfuscated = config.getBoolean("block-ip.detect-obfuscated", true);
        ipBypassPermission = config.getString("block-ip.bypass-permission", "hippotab.chatfilter.bypass.ip");
        discordWebhookUrl = config.getString("block-ip.discord-webhook-url", "");
        ipTargets = loadTargets(config.getStringList("block-ip.targets"));
        blockDomain = config.getBoolean("block-domain.enabled", false);
        domainDetectObfuscated = config.getBoolean("block-domain.detect-obfuscated", true);
        domainBypassPermission = config.getString("block-domain.bypass-permission", "hippotab.chatfilter.bypass.domain");
        domainDiscordWebhookUrl = config.getString("block-domain.discord-webhook-url", "");
        domainTlds = loadTlds(config.getStringList("block-domain.tlds"));
        repeatBypassPermission = config.getString("repeat-limit.bypass-permission", "hippotab.chatfilter.bypass.repeat");
        duplicateBypassPermission = config.getString("duplicate-limit.bypass-permission", "hippotab.chatfilter.bypass.repeat");
        spamSettings = loadSpamSettings(config);
        spamCommands = config.getBoolean("spam-commands.enabled", true)
                ? loadTlds(config.getStringList("spam-commands.list")) : Set.of();

        loadCustomConfig();
        rebuildWords();

        if (!enabled) {
            return;
        }
        List<String> sources = List.copyOf(config.getStringList("wordlist"));
        int generation = loadGeneration.incrementAndGet();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> loadSources(sources, generation));
    }

    private void loadSources(List<String> sources, int generation) {
        Set<String> loaded = new LinkedHashSet<>();
        for (String source : sources) {
            loaded.addAll(loadWordsFromSource(source));
        }
        synchronized (this) {
            // A newer reload started while this one was downloading; its result wins.
            if (generation != loadGeneration.get()) {
                return;
            }
            sourceWords = Set.copyOf(loaded);
            rebuildWords();
        }
        plugin.getLogger().info("Chat filter loaded " + matcher.wordCount() + " filter words.");
    }

    private synchronized void rebuildWords() {
        Set<String> merged = new LinkedHashSet<>(sourceWords);
        merged.addAll(customConfig.getStringList("list"));
        matcher = new WordMatcher(merged, customConfig.getStringList("whitelist"), ignoreSeparators);
    }

    public boolean addCustomWord(String word) {
        return addTo("list", word);
    }

    public boolean removeCustomWord(String word) {
        return removeFrom("list", word);
    }

    public List<String> getCustomWords() {
        return entries("list");
    }

    /** Adds a phrase that is never censored (also disables a filter word of the same text). */
    public boolean addAllowedPhrase(String phrase) {
        return addTo("whitelist", phrase);
    }

    public boolean removeAllowedPhrase(String phrase) {
        return removeFrom("whitelist", phrase);
    }

    public List<String> getAllowedPhrases() {
        return entries("whitelist");
    }

    private synchronized boolean addTo(String key, String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty() || WordMatcher.fold(trimmed).isEmpty()) {
            return false;
        }
        List<String> list = new ArrayList<>(customConfig.getStringList(key));
        if (list.contains(trimmed)) {
            return false;
        }
        list.add(trimmed);
        customConfig.set(key, list);
        saveCustomConfig();
        rebuildWords();
        return true;
    }

    private synchronized boolean removeFrom(String key, String value) {
        String trimmed = value.trim();
        List<String> list = new ArrayList<>(customConfig.getStringList(key));
        if (!list.removeIf(entry -> entry.equals(trimmed))) {
            return false;
        }
        customConfig.set(key, list);
        saveCustomConfig();
        rebuildWords();
        return true;
    }

    private synchronized List<String> entries(String key) {
        return new ArrayList<>(customConfig.getStringList(key));
    }

    /** Finds and censors filter words in one pass. */
    public WordMatcher.Result scan(String input) {
        return matcher.scan(input);
    }

    public String filterText(String input) {
        return matcher.scan(input).censored();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getWordCount() {
        return matcher.wordCount();
    }

    public String getServerName() {
        return serverName;
    }

    public long getDiscordCooldownMillis() {
        return discordCooldownMillis;
    }

    public boolean isWebUrlBlockEnabled() {
        return blockWebUrls;
    }

    public String getWebUrlBypassPermission() {
        return webUrlBypassPermission;
    }

    public boolean isIpBlockEnabled() {
        return blockIp;
    }

    public boolean isIpDetectObfuscated() {
        return ipDetectObfuscated;
    }

    public String getIpBypassPermission() {
        return ipBypassPermission;
    }

    public String getDiscordWebhookUrl() {
        return discordWebhookUrl;
    }

    public List<Pattern> getIpTargets() {
        return ipTargets;
    }

    private List<Pattern> loadTargets(List<String> raw) {
        List<Pattern> patterns = new ArrayList<>();
        for (String entry : raw) {
            Pattern compiled = IpDetector.compileTarget(entry);
            if (compiled != null) {
                patterns.add(compiled);
            }
        }
        return List.copyOf(patterns);
    }

    public boolean isDomainBlockEnabled() {
        return blockDomain;
    }

    public boolean isDomainDetectObfuscated() {
        return domainDetectObfuscated;
    }

    public String getDomainBypassPermission() {
        return domainBypassPermission;
    }

    public String getDomainDiscordWebhookUrl() {
        return domainDiscordWebhookUrl;
    }

    public Set<String> getDomainTlds() {
        return domainTlds;
    }

    public SpamGuard.Settings getSpamSettings() {
        return spamSettings;
    }

    /** Whether this command (lower-case, no slash or namespace) counts towards the spam limits. */
    public boolean isSpamCommand(String label) {
        return spamCommands.contains(label);
    }

    private SpamGuard.Settings loadSpamSettings(ConfigurationSection config) {
        // A window of 0 used to mean "never resets", which locked a player out for the
        // rest of the session after max-repeats messages; the window is at least 1 s now.
        long rateWindow = Math.max(1L, config.getLong("repeat-limit.expire-seconds", 3L)) * 1000L;
        double similarity = Math.max(1, Math.min(100, config.getInt("duplicate-limit.similarity", 80))) / 100.0;

        long[] mutes = new long[0];
        if (config.getBoolean("spam-penalty.enabled", true)) {
            List<Integer> seconds = config.getIntegerList("spam-penalty.mute-seconds");
            mutes = new long[seconds.size()];
            for (int i = 0; i < mutes.length; i++) {
                mutes[i] = Math.max(0, seconds.get(i)) * 1000L;
            }
        }
        return new SpamGuard.Settings(
                config.getBoolean("repeat-limit.enabled", false),
                config.getInt("repeat-limit.max-repeats", 4),
                rateWindow,
                config.getBoolean("duplicate-limit.enabled", false),
                config.getInt("duplicate-limit.max-same", 2),
                Math.max(0L, config.getLong("duplicate-limit.expire-seconds", 30L)) * 1000L,
                Math.max(1, config.getInt("duplicate-limit.history", 5)),
                similarity,
                mutes,
                Math.max(1L, config.getLong("spam-penalty.reset-seconds", 600L)) * 1000L);
    }

    public String getRepeatBypassPermission() {
        return repeatBypassPermission;
    }

    public String getDuplicateBypassPermission() {
        return duplicateBypassPermission;
    }

    private Set<String> loadTlds(List<String> raw) {
        Set<String> set = new LinkedHashSet<>();
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            String tld = entry.trim().toLowerCase(Locale.ROOT);
            if (tld.startsWith(".")) {
                tld = tld.substring(1);
            }
            if (!tld.isEmpty()) {
                set.add(tld);
            }
        }
        return Set.copyOf(set);
    }

    private void loadCustomConfig() {
        customFile = new File(plugin.getDataFolder(), CUSTOM_FILE);
        if (!customFile.exists()) {
            importLegacyCustomWords();
        }
        customConfig = YamlConfiguration.loadConfiguration(customFile);
    }

    /** One-time carry-over of the standalone HippoChatFilter plugin's custom word list. */
    private void importLegacyCustomWords() {
        File legacy = new File(plugin.getDataFolder().getParentFile(), "HippoChatFilter/custom.yml");
        if (!legacy.isFile()) {
            return;
        }
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            Files.copy(legacy.toPath(), customFile.toPath());
            plugin.getLogger().info("Imported custom filter words from " + legacy.getPath());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to import " + legacy.getPath() + ": " + e.getMessage());
        }
    }

    /** Counts saves, so a slow older write can never overwrite a newer one. */
    private final java.util.concurrent.atomic.AtomicLong customSaveSequence = new java.util.concurrent.atomic.AtomicLong();
    private final Object customSaveLock = new Object();
    private long customSaveWritten;

    private void saveCustomConfig() {
        // Serialise on the calling thread, write on an async one.
        String data = customConfig.saveToString();
        long sequence = customSaveSequence.incrementAndGet();
        Runnable write = () -> {
            synchronized (customSaveLock) {
                if (sequence < customSaveWritten) {
                    return;
                }
                try {
                    Files.writeString(customFile.toPath(), data, StandardCharsets.UTF_8);
                    customSaveWritten = sequence;
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to save " + CUSTOM_FILE + ": " + e.getMessage());
                }
            }
        };
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
        } else {
            write.run();
        }
    }

    private List<String> loadWordsFromSource(String source) {
        String trimmed = source == null ? "" : source.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return loadRemoteWords(trimmed);
        }
        File local = new File(trimmed);
        if (!local.isAbsolute()) {
            local = new File(plugin.getDataFolder(), trimmed);
        }
        if (local.isFile()) {
            try {
                return parseWords(Files.readAllLines(local.toPath(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load local word file: " + local.getPath() + " (" + e.getMessage() + ")");
                return List.of();
            }
        }
        return parseWords(List.of(trimmed));
    }

    /**
     * Downloads a word list and keeps a copy on disk. When the download fails (host
     * down, no network at startup) the last good copy is used, so the filter does not
     * silently shrink to the custom words only.
     */
    private List<String> loadRemoteWords(String source) {
        Path cache = cacheFile(source);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(source).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "HippoTab-ChatFilter/1.0");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }

            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            List<String> parsed = parseWords(lines);
            if (parsed.isEmpty()) {
                throw new IllegalStateException("empty list");
            }
            writeCache(cache, parsed);
            return parsed;
        } catch (Exception e) {
            if (Files.isRegularFile(cache)) {
                try {
                    List<String> cached = parseWords(Files.readAllLines(cache, StandardCharsets.UTF_8));
                    plugin.getLogger().warning("Failed to load word source: " + source + " (" + e.getMessage()
                            + "); using the saved copy with " + cached.size() + " words.");
                    return cached;
                } catch (Exception cacheError) {
                    plugin.getLogger().warning("Failed to read the saved copy of " + source + ": " + cacheError.getMessage());
                }
            }
            plugin.getLogger().warning("Failed to load word source: " + source + " (" + e.getMessage()
                    + ") and there is no saved copy; its words are NOT being filtered.");
            return List.of();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Path cacheFile(String source) {
        String name;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(source.getBytes(StandardCharsets.UTF_8));
            name = HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            name = Integer.toHexString(source.hashCode());
        }
        return plugin.getDataFolder().toPath().resolve(CACHE_DIR).resolve(name + ".txt");
    }

    private void writeCache(Path cache, List<String> words) {
        try {
            Files.createDirectories(cache.getParent());
            Path temp = cache.resolveSibling(cache.getFileName() + ".tmp");
            Files.write(temp, words, StandardCharsets.UTF_8);
            Files.move(temp, cache, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save a copy of a word list: " + e.getMessage());
        }
    }

    private List<String> parseWords(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String word = raw.trim();
            if (!word.isEmpty() && !word.startsWith("#")) {
                out.add(word);
            }
        }
        return out;
    }
}
