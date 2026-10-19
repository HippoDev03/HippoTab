package net.mwtw.hippoNick.service;

import net.mwtw.hippoNick.Core;
import net.mwtw.hippoNick.domain.DisplayState;
import net.mwtw.hippoNick.domain.NickProfile;
import net.mwtw.hippoNick.history.NickHistoryService;
import net.mwtw.hippoNick.storage.FileNickRepository;
import net.mwtw.hippoNick.storage.NickRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

public final class NickManager {
    private static final Pattern NAMEMC_IMAGE_PATH = Pattern.compile("^/i/([0-9a-fA-F]{32,64})\\.png$");
    private static final Pattern VALID_NICKNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern VALID_SKIN_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    // Actor recorded in the nick history when a nick is dropped because of a name clash.
    private static final String NAME_CONFLICT_ACTOR = "系統(名稱衝突)";

    private final Core plugin;
    private final NickRepository repository;
    private final RankService rankService;
    private final NametagService nametagService;
    private final Map<UUID, PlayerProfile> originalProfiles = new ConcurrentHashMap<>();
    private final Map<UUID, Set<com.destroystokyo.paper.profile.ProfileProperty>> originalProfileProperties = new ConcurrentHashMap<>();
    private final Map<UUID, String> appliedSkinSource = new ConcurrentHashMap<>();
    private final Map<UUID, String> appliedPacketName = new ConcurrentHashMap<>();
    // The login name per online player. setPlayerProfile() renames the server-side
    // GameProfile, so player.getName() returns the NICK while nicked — every place that
    // needs the real name must go through realName(player) instead.
    private final Map<UUID, String> realNames = new ConcurrentHashMap<>();
    private NickHistoryService history;

    public NickManager(Core plugin, NickRepository repository, RankService rankService, NametagService nametagService) {
        this.plugin = plugin;
        this.repository = repository;
        this.rankService = rankService;
        this.nametagService = nametagService;
    }

    public void setNicknameAndRank(Player player, String nickname, String rankOverride) {
        setNicknameAndRank(player, nickname, rankOverride, null);
    }

    public void setHistory(NickHistoryService history) {
        this.history = history;
    }

    private void record(Player player, String action, String nickname, String rank, String skin, String actor) {
        if (history != null) {
            history.record(player.getUniqueId(), realName(player), action, nickname, rank, skin, actor);
        }
    }

    public void setNicknameAndRank(Player player, String nickname, String rankOverride, String skinName) {
        setNicknameAndRank(player, nickname, rankOverride, skinName, realName(player));
    }

    /** @param actor who made the change (a real name, or the console), for the nick history */
    public void setNicknameAndRank(Player player, String nickname, String rankOverride, String skinName, String actor) {
        if (!isValidEnglishNickname(nickname)) {
            throw new IllegalArgumentException("Nickname must be 3-16 characters: letters, digits or underscore.");
        }
        NickProfile profile = repository.find(player.getUniqueId())
                .orElse(new NickProfile(player.getUniqueId(), null, null, null, Instant.now()));
        profile = profile.withNickname(nickname).withRankOverride(rankOverride).withSkinName(skinName);
        repository.save(profile);
        record(player, NickHistoryService.SET, nickname, rankOverride, skinName, actor);
        applyDisplay(player);
    }

    public void clear(Player player) {
        clear(player, realName(player));
    }

    public void clear(Player player, String actor) {
        Optional<NickProfile> previous = repository.find(player.getUniqueId());
        repository.delete(player.getUniqueId());
        if (previous.isPresent()) {
            record(player, NickHistoryService.CLEAR,
                    previous.get().nickname(), previous.get().rankOverride(), null, actor);
        }
        applyDisplay(player);
    }

    public boolean setRankOverride(Player player, String rankOverride, String actor) {
        Optional<String> canonical = rankService.findCanonicalRank(rankOverride);
        if (canonical.isEmpty()) {
            return false;
        }
        NickProfile profile = repository.find(player.getUniqueId())
                .orElse(new NickProfile(player.getUniqueId(), null, null, null, Instant.now()));
        repository.save(profile.withRankOverride(canonical.get()));
        record(player, NickHistoryService.RANK, profile.nickname(), canonical.get(), null, actor);
        applyDisplay(player);
        return true;
    }

    /** Records the login name; call on join BEFORE any profile rewrite. */
    public void rememberRealName(Player player) {
        realNames.put(player.getUniqueId(), player.getName());
    }

    /** The player's real login name, even while their profile is renamed to a nick. */
    public String realName(Player player) {
        String remembered = realNames.get(player.getUniqueId());
        return remembered == null ? player.getName() : remembered;
    }

    /** The player's current nickname, or null when they are not nicked. */
    public String nicknameOf(Player player) {
        return repository.find(player.getUniqueId())
                .map(NickProfile::nickname)
                .filter(value -> !value.isBlank())
                .orElse(null);
    }

    /**
     * Applies a stored nick during PlayerJoinEvent, before the join message is built and
     * before the player is listed for anyone — so the real name never reaches other
     * clients. The overhead nametag and tab entry follow on the normal join path.
     */
    public void applyOnJoin(Player player) {
        Optional<NickProfile> profile = repository.find(player.getUniqueId());
        boolean nicked = profile.map(NickProfile::nickname).filter(value -> !value.isBlank()).isPresent();
        if (!nicked) {
            return;
        }
        DisplayState state = resolveDisplay(player, profile);
        applyBukkitNames(player, state.effectiveName());
        syncProfile(player, profile, state);
    }

    /**
     * Resolves name collisions caused by a join: an online player nicked as the joiner's
     * real name loses that nick, and the joiner's own stored nick is dropped when it now
     * matches someone online. Returns the players whose nick was cleared.
     */
    public List<Player> clearConflictingNicks(Player joined) {
        ArrayList<Player> cleared = new ArrayList<>();
        String real = realName(joined);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(joined.getUniqueId())) {
                continue;
            }
            String nick = nicknameOf(online);
            if (nick != null && nick.equalsIgnoreCase(real)) {
                clear(online, NAME_CONFLICT_ACTOR);
                cleared.add(online);
            }
        }
        String ownNick = nicknameOf(joined);
        if (ownNick != null && conflictsWithOnlineRealName(joined, ownNick)) {
            repository.delete(joined.getUniqueId());
            record(joined, NickHistoryService.CLEAR, ownNick, null, null, NAME_CONFLICT_ACTOR);
            cleared.add(joined);
        }
        return cleared;
    }

    /**
     * Puts the real profile name back right now, without scheduling anything. Used on
     * quit (Paper removes the player from its by-name index using the CURRENT profile
     * name, so a still-renamed profile would leave the real-name entry behind) and on
     * plugin disable.
     */
    public void restoreRealProfileNow(Player player) {
        UUID uuid = player.getUniqueId();
        String real = realNames.get(uuid);
        if (real == null || real.equals(player.getName())) {
            return;
        }
        try {
            com.destroystokyo.paper.profile.PlayerProfile restored = Bukkit.createProfile(uuid, real);
            Set<com.destroystokyo.paper.profile.ProfileProperty> originalProps = originalProfileProperties.get(uuid);
            if (hasTextureProperty(originalProps)) {
                restored.setProperties(originalProps);
            } else {
                restored.setProperties(player.getPlayerProfile().getProperties());
            }
            player.setPlayerProfile(restored);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not restore the real profile name for " + real + ": " + exception.getMessage());
        }
    }

    /** Plugin disable: hand every renamed profile back, the maps die with the plugin. */
    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            restoreRealProfileNow(player);
            if (hasRuntimeProfileState(player.getUniqueId())) {
                restoreBukkitNames(player);
            }
        }
        originalProfiles.clear();
        originalProfileProperties.clear();
        appliedSkinSource.clear();
        appliedPacketName.clear();
    }

    public void applyDisplay(Player player) {
        Optional<NickProfile> profile = repository.find(player.getUniqueId());
        DisplayState state = resolveDisplay(player, profile);
        boolean nicked = profile.map(NickProfile::nickname).filter(value -> !value.isBlank()).isPresent();
        if (nicked) {
            applyBukkitNames(player, state.effectiveName());
            nametagService.apply(player, state);
        } else {
            restoreBukkitNames(player);
            nametagService.clear(player);
            if (!hasRuntimeProfileState(player.getUniqueId())) {
                return;
            }
        }
        syncProfile(player, profile, state);
        refreshHippoTab(player);
    }

    private void refreshHippoTab(Player player) {
        // Same plugin now — refresh the tab entry directly. The overhead nametag is
        // refreshed separately via nametagService (PacketNametagService) in applyDisplay.
        plugin.refreshTab(player);
    }

    public void applyDisplayToOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyDisplay(player);
        }
    }

    public void refreshNickedSkinsForViewers() {
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (!isNicked(target)) {
                continue;
            }
            refreshForViewers(target);
        }
    }

    public void refreshNickedSkinsForViewer(Player viewer) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (!isNicked(target)) {
                continue;
            }
            if (target.getUniqueId().equals(viewer.getUniqueId())) {
                continue;
            }
            refreshForViewer(viewer, target);
        }
    }

    public DisplayState resolveDisplay(Player player) {
        Optional<NickProfile> profile = repository.find(player.getUniqueId());
        return resolveDisplay(player, profile);
    }

    public boolean isNicked(Player player) {
        return repository.find(player.getUniqueId())
                .map(NickProfile::nickname)
                .filter(value -> !value.isBlank())
                .isPresent();
    }

    public String resolvePacketProfileName(Player player) {
        DisplayState display = resolveDisplay(player);
        String resolved = resolvePacketName(player, display.effectiveName());
        return resolved == null || resolved.isBlank() ? realName(player) : resolved;
    }

    private DisplayState resolveDisplay(Player player, Optional<NickProfile> profile) {
        String effectiveName = profile.map(NickProfile::nickname)
                .filter(s -> !s.isBlank())
                .orElse(realName(player));
        String effectiveRank = profile.map(NickProfile::rankOverride)
                .filter(s -> !s.isBlank())
                .orElse(rankService.resolveBaseRank(player));
        return new DisplayState(effectiveName, effectiveRank);
    }

    public DisplayState resolveStoredDisplay(UUID uuid, String realNameFallback) {
        // Offline player, asked from a placeholder on the main thread: never query storage here.
        Optional<NickProfile> profile = repository.findCached(uuid);
        String effectiveName = profile.map(NickProfile::nickname)
                .filter(s -> !s.isBlank())
                .orElse(realNameFallback == null ? "" : realNameFallback);
        String effectiveRank = profile.map(NickProfile::rankOverride)
                .filter(s -> !s.isBlank())
                .orElse("default");
        return new DisplayState(effectiveName, effectiveRank);
    }

    /**
     * The stored profile, without ever blocking on storage: online players are always
     * cached (warmed at login); an offline player's profile is loaded in the background
     * on first request and reads as empty until that finishes.
     */
    public Optional<NickProfile> findProfile(UUID uuid) {
        return repository.findCached(uuid);
    }

    public Optional<Player> resolveRealPlayer(String query) {
        Player exact = Bukkit.getPlayerExact(query);
        if (exact != null) {
            return Optional.of(exact);
        }

        String lowered = query.toLowerCase(Locale.ROOT);
        for (Player player : Bukkit.getOnlinePlayers()) {
            DisplayState state = resolveDisplay(player);
            if (state.effectiveName().equalsIgnoreCase(query)) {
                return Optional.of(player);
            }
            if (state.effectiveName().toLowerCase(Locale.ROOT).contains(lowered)) {
                return Optional.of(player);
            }
        }
        return Optional.empty();
    }

    public Optional<Player> resolveRealPlayerExact(String query) {
        Player exact = Bukkit.getPlayerExact(query);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            DisplayState state = resolveDisplay(player);
            if (state.effectiveName().equalsIgnoreCase(query)) {
                return Optional.of(player);
            }
        }
        return Optional.empty();
    }

    public Collection<String> onlineDisplayNames() {
        ArrayList<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(resolveDisplay(player).effectiveName());
        }
        return names;
    }

    public Collection<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
                .map(this::realName)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * True when the nickname equals an online player's real name (including the player's
     * own) or the nick another online player is already using.
     */
    public boolean conflictsWithOnlineRealName(Player self, String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return false;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (realName(online).equalsIgnoreCase(nickname)) {
                return true;
            }
            if (self != null && online.getUniqueId().equals(self.getUniqueId())) {
                continue;
            }
            String otherNick = nicknameOf(online);
            if (otherNick != null && otherNick.equalsIgnoreCase(nickname)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isValidEnglishNickname(String nickname) {
        return nickname != null && VALID_NICKNAME.matcher(nickname).matches();
    }

    public List<Player> onlineNickedPlayers() {
        ArrayList<Player> players = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (isNicked(online)) {
                players.add(online);
            }
        }
        return players;
    }

    public void clearRuntimeState(UUID uuid) {
        originalProfiles.remove(uuid);
        originalProfileProperties.remove(uuid);
        appliedSkinSource.remove(uuid);
        appliedPacketName.remove(uuid);
        realNames.remove(uuid);
        repository.invalidate(uuid);
    }

    public void preloadProfile(UUID uuid) {
        repository.preload(uuid);
    }

    public int migrateFromFile(Path filePath) {
        FileNickRepository fileRepo = new FileNickRepository(filePath);
        int count = 0;
        try {
            for (NickProfile profile : fileRepo.findAll()) {
                repository.save(profile);
                count++;
            }
        } finally {
            fileRepo.close();
        }
        return count;
    }

    private void syncProfile(Player player, Optional<NickProfile> profile, DisplayState state) {
        String nickname = profile.map(NickProfile::nickname).orElse(null);
        if (nickname == null || nickname.isBlank()) {
            restoreProfile(player);
            return;
        }

        String packetName = resolvePacketName(player, state.effectiveName());
        if (packetName == null) {
            packetName = realName(player);
        }

        boolean skinEnabled = plugin.getConfig().getBoolean("nick.skin.enabled", false);
        String customSkin = profile.map(NickProfile::skinName).orElse(null);
        SkinSource skinSource = customSkin == null || customSkin.isBlank()
                ? resolveDefaultSkinSource(player, state.effectiveName())
                : parseSkinSource(customSkin);
        if (skinEnabled && skinSource == null) {
            skinSource = SkinSource.forMojangName(realName(player));
        }
        String skinSourceKey = skinSource == null ? null : skinSource.sourceKey();
        String current = appliedSkinSource.get(player.getUniqueId());
        String currentPacketName = appliedPacketName.get(player.getUniqueId());
        if ((!skinEnabled || (skinSourceKey != null && skinSourceKey.equalsIgnoreCase(current)))
                && packetName.equalsIgnoreCase(currentPacketName == null ? "" : currentPacketName)) {
            return;
        }

        realNames.putIfAbsent(player.getUniqueId(), player.getName());
        originalProfiles.putIfAbsent(player.getUniqueId(), player.getPlayerProfile().clone());
        if (player.getPlayerProfile() instanceof com.destroystokyo.paper.profile.PlayerProfile paperProfile) {
            originalProfileProperties.putIfAbsent(player.getUniqueId(), new HashSet<>(paperProfile.getProperties()));
        }
        if (!skinEnabled) {
            applyProfile(player, packetName, player.getPlayerProfile());
            appliedPacketName.put(player.getUniqueId(), packetName);
            appliedSkinSource.remove(player.getUniqueId());
            return;
        }

        if (skinSource == null) {
            applyProfile(player, packetName, player.getPlayerProfile());
            appliedPacketName.put(player.getUniqueId(), packetName);
            appliedSkinSource.remove(player.getUniqueId());
            return;
        }
        if (skinSource.skinUrl() != null) {
            applyProfileWithSkinUrl(player, packetName, skinSource.skinUrl(), skinSource.sourceKey());
            return;
        }

        // The skin lookup below is async. Rename right away with the current textures so
        // the nick is in place immediately (matters on join); the skin follows.
        if (!packetName.equalsIgnoreCase(currentPacketName == null ? "" : currentPacketName)) {
            applyProfile(player, packetName, player.getPlayerProfile());
            appliedPacketName.put(player.getUniqueId(), packetName);
        }

        String skinName = skinSource.mojangName();
        com.destroystokyo.paper.profile.PlayerProfile lookup = Bukkit.createProfile(skinName);
        final String finalSkinName = skinSource.sourceKey();
        final String finalPacketName = packetName;
        final String loggedName = realName(player);
        lookup.update().thenAccept(updated -> Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(player.getUniqueId());
            if (online == null || !online.isOnline() || !isNicked(online)) {
                return;
            }
            applyProfile(online, finalPacketName, updated);
            appliedSkinSource.put(online.getUniqueId(), finalSkinName);
            appliedPacketName.put(online.getUniqueId(), finalPacketName);
        })).exceptionally(error -> {
            plugin.getLogger().warning("Failed to update skin for " + loggedName + " via source " + finalSkinName);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(player.getUniqueId());
                if (online == null || !online.isOnline() || !isNicked(online)) {
                    return;
                }
                applyProfile(online, finalPacketName, online.getPlayerProfile());
                appliedPacketName.put(online.getUniqueId(), finalPacketName);
            });
            return null;
        });
    }

    private void restoreProfile(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerProfile original = originalProfiles.remove(uuid);
        Set<com.destroystokyo.paper.profile.ProfileProperty> originalProps = originalProfileProperties.remove(uuid);
        String removedSkinSource = appliedSkinSource.remove(uuid);
        String removedPacketName = appliedPacketName.remove(uuid);
        // NOT player.getName(): the profile is still renamed to the nick at this point.
        String realName = realName(player);
        boolean hadRuntimeRewrite = original != null
                || (originalProps != null && !originalProps.isEmpty())
                || removedSkinSource != null
                || removedPacketName != null;
        if (!hadRuntimeRewrite) {
            return;
        }
        boolean hadCache = original != null;

        if (original != null) {
            com.destroystokyo.paper.profile.PlayerProfile restoredFromCache =
                    Bukkit.createProfile(original.getUniqueId(), realName);
            if (hasTextureProperty(originalProps)) {
                restoredFromCache.setProperties(originalProps);
            } else if (original instanceof com.destroystokyo.paper.profile.PlayerProfile paperOriginal
                    && hasTextureProperty(paperOriginal)) {
                restoredFromCache.setProperties(paperOriginal.getProperties());
            } else {
                restoredFromCache.setTextures(original.getTextures());
            }
            player.setPlayerProfile(restoredFromCache);
            refreshForViewers(player);
        }

        // Always do an authoritative refresh from Mojang profile data, so /unnick
        // reliably returns to the true original skin even if cache was stale/missing.
        restoreAuthoritativeAsync(uuid, realName, hadCache, 0);
    }

    private boolean hasRuntimeProfileState(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        return originalProfiles.containsKey(uuid)
                || originalProfileProperties.containsKey(uuid)
                || appliedSkinSource.containsKey(uuid)
                || appliedPacketName.containsKey(uuid);
    }

    private void restoreAuthoritativeAsync(UUID uuid, String realName, boolean hadCache, int attempt) {
        com.destroystokyo.paper.profile.PlayerProfile authoritative = Bukkit.createProfile(realName);
        authoritative.update().thenAccept(updated -> Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || !online.isOnline() || isNicked(online)) {
                return;
            }
            if (!hasTextureProperty(updated)) {
                if (attempt < 2) {
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> restoreAuthoritativeAsync(uuid, realName, hadCache, attempt + 1), 40L);
                } else if (!hadCache) {
                    plugin.getLogger().warning("Could not restore original skin textures for " + realName + " after retries.");
                }
                return;
            }
            com.destroystokyo.paper.profile.PlayerProfile finalProfile =
                    Bukkit.createProfile(online.getUniqueId(), realName);
            finalProfile.setProperties(updated.getProperties());
            online.setPlayerProfile(finalProfile);
            refreshForViewers(online);
        })).exceptionally(error -> {
            if (attempt < 2) {
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> restoreAuthoritativeAsync(uuid, realName, hadCache, attempt + 1), 40L);
            } else if (!hadCache) {
                plugin.getLogger().warning("Failed to refresh original skin for " + realName + " on /unnick");
            }
            return null;
        });
    }

    private void applyProfile(Player player, String packetName, org.bukkit.profile.PlayerProfile sourceProfile) {
        com.destroystokyo.paper.profile.PlayerProfile rewritten =
                Bukkit.createProfile(player.getUniqueId(), packetName);
        if (sourceProfile instanceof com.destroystokyo.paper.profile.PlayerProfile paperSource
                && hasTextureProperty(paperSource)) {
            rewritten.setProperties(paperSource.getProperties());
        } else {
            rewritten.setTextures(sourceProfile.getTextures());
        }
        player.setPlayerProfile(rewritten);
        refreshForViewers(player);
    }

    private void applyProfileWithSkinUrl(Player player, String packetName, URL skinUrl, String sourceKey) {
        com.destroystokyo.paper.profile.PlayerProfile sourceProfile =
                Bukkit.createProfile(player.getUniqueId(), packetName);
        sourceProfile.setProperties(Set.of(new com.destroystokyo.paper.profile.ProfileProperty(
                "textures", texturePropertyValue(skinUrl))));
        player.setPlayerProfile(sourceProfile);
        refreshForViewers(player);
        appliedSkinSource.put(player.getUniqueId(), sourceKey);
        appliedPacketName.put(player.getUniqueId(), packetName);
    }

    private String texturePropertyValue(URL skinUrl) {
        String safeUrl = skinUrl.toExternalForm().replace("\\", "\\\\").replace("\"", "\\\"");
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + safeUrl + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private void refreshForViewers(Player target) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(target.getUniqueId())) {
                continue;
            }
            refreshForViewer(viewer, target);
        }
    }

    private void refreshForViewer(Player viewer, Player target) {
        viewer.hidePlayer(plugin, target);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (viewer.isOnline() && target.isOnline()) {
                viewer.showPlayer(plugin, target);
            }
        }, 2L);
    }

    private SkinSource resolveDefaultSkinSource(Player player, String effectiveName) {
        String mode = plugin.getConfig().getString("nick.skin.source", "nickname");
        String raw;
        if ("player".equalsIgnoreCase(mode)) {
            raw = realName(player);
        } else if ("fixed".equalsIgnoreCase(mode)) {
            raw = plugin.getConfig().getString("nick.skin.fixed-name", "");
        } else {
            raw = effectiveName;
        }
        String candidate = sanitizeSkinName(raw);
        if (candidate != null) {
            return SkinSource.forMojangName(candidate);
        }
        if (plugin.getConfig().getBoolean("nick.skin.fallback-to-real", true)) {
            return SkinSource.forMojangName(realName(player));
        }
        return null;
    }

    private String resolvePacketName(Player player, String effectiveName) {
        if (!plugin.getConfig().getBoolean("nick.packet-name.enabled", true)) {
            return realName(player);
        }
        String candidate = sanitizeSkinName(effectiveName);
        if (candidate != null) {
            return candidate;
        }
        if (plugin.getConfig().getBoolean("nick.packet-name.fallback-to-real", true)) {
            return realName(player);
        }
        return null;
    }

    private String sanitizeSkinName(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace('§', '&').replaceAll("&[0-9a-fk-orA-FK-OR]", "");
        cleaned = cleaned.replaceAll("[^A-Za-z0-9_]", "");
        if (!VALID_SKIN_NAME.matcher(cleaned).matches()) {
            return null;
        }
        return cleaned;
    }

    private SkinSource parseSkinSource(String raw) {
        URL skinUrl = parseSkinUrl(raw);
        if (skinUrl != null) {
            return SkinSource.forUrl(skinUrl);
        }
        String skinName = sanitizeSkinName(raw);
        if (skinName != null) {
            return SkinSource.forMojangName(skinName);
        }
        return null;
    }

    /**
     * Parses a skin texture URL, or returns null when it is not one the client will load.
     * NameMC image links are mapped to the underlying Mojang texture; any other host must
     * be a Mojang texture host, because the vanilla client refuses textures from elsewhere.
     */
    public static URL parseSkinUrl(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            String normalized = raw.trim().replaceAll("(?i)%C2%B7+$", "");
            while (normalized.endsWith("·")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            URI uri = new URI(normalized);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if ("s.namemc.com".equals(host)) {
                java.util.regex.Matcher matcher = NAMEMC_IMAGE_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
                if (matcher.matches()) {
                    return URI.create("https://textures.minecraft.net/texture/" + matcher.group(1)).toURL();
                }
            }
            if (!host.endsWith(".minecraft.net") && !host.endsWith(".mojang.com")) {
                return null;
            }
            return uri.toURL();
        } catch (URISyntaxException | MalformedURLException exception) {
            return null;
        }
    }

    private record SkinSource(String sourceKey, String mojangName, URL skinUrl) {
        private static SkinSource forMojangName(String mojangName) {
            return new SkinSource(mojangName, mojangName, null);
        }

        private static SkinSource forUrl(URL skinUrl) {
            return new SkinSource(skinUrl.toExternalForm(), null, skinUrl);
        }
    }

    private boolean hasTextureProperty(PlayerProfile profile) {
        if (!(profile instanceof com.destroystokyo.paper.profile.PlayerProfile paper)) {
            return false;
        }
        return hasTextureProperty(paper.getProperties());
    }

    private boolean hasTextureProperty(Set<com.destroystokyo.paper.profile.ProfileProperty> properties) {
        if (properties == null || properties.isEmpty()) {
            return false;
        }
        for (com.destroystokyo.paper.profile.ProfileProperty property : properties) {
            if ("textures".equalsIgnoreCase(property.getName())
                    && property.getValue() != null
                    && !property.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTextureProperty(java.util.Collection<com.destroystokyo.paper.profile.ProfileProperty> properties) {
        if (properties == null || properties.isEmpty()) {
            return false;
        }
        for (com.destroystokyo.paper.profile.ProfileProperty property : properties) {
            if ("textures".equalsIgnoreCase(property.getName())
                    && property.getValue() != null
                    && !property.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    // Sets the Bukkit display name to the nickname. HippoTab's nametag service uses
    // this (player.displayName() differing from the real name) as the "middle" of the
    // head nametag, so the nick shows above the head out of the box. We intentionally
    // leave playerListName alone — the tab list is owned by the packet rewrite and
    // HippoTab, and writing it here too would cause flicker between the two.
    private void applyBukkitNames(Player player, String effectiveName) {
        if (effectiveName == null || effectiveName.isBlank()) {
            return;
        }
        player.displayName(net.kyori.adventure.text.Component.text(effectiveName));
    }

    private void restoreBukkitNames(Player player) {
        player.displayName(null);
    }
}
