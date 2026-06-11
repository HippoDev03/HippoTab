package net.mwtw.hippoNick.service;

import net.mwtw.hippoNick.Core;
import net.mwtw.hippoNick.domain.DisplayState;
import net.mwtw.hippoNick.domain.NickProfile;
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

    private final Core plugin;
    private final NickRepository repository;
    private final RankService rankService;
    private final NametagService nametagService;
    private final Map<UUID, PlayerProfile> originalProfiles = new ConcurrentHashMap<>();
    private final Map<UUID, Set<com.destroystokyo.paper.profile.ProfileProperty>> originalProfileProperties = new ConcurrentHashMap<>();
    private final Map<UUID, String> appliedSkinSource = new ConcurrentHashMap<>();
    private final Map<UUID, String> appliedPacketName = new ConcurrentHashMap<>();

    public NickManager(Core plugin, NickRepository repository, RankService rankService, NametagService nametagService) {
        this.plugin = plugin;
        this.repository = repository;
        this.rankService = rankService;
        this.nametagService = nametagService;
    }

    public void setNicknameAndRank(Player player, String nickname, String rankOverride) {
        setNicknameAndRank(player, nickname, rankOverride, null);
    }

    public void setNicknameAndRank(Player player, String nickname, String rankOverride, String skinName) {
        if (!isValidEnglishNickname(nickname)) {
            throw new IllegalArgumentException("Nickname must be 3-16 characters: letters, digits or underscore.");
        }
        NickProfile profile = repository.find(player.getUniqueId())
                .orElse(new NickProfile(player.getUniqueId(), null, null, null, Instant.now()));
        profile = profile.withNickname(nickname).withRankOverride(rankOverride).withSkinName(skinName);
        repository.save(profile);
        applyDisplay(player);
    }

    public void clear(Player player) {
        repository.delete(player.getUniqueId());
        applyDisplay(player);
    }

    public boolean setRankOverride(Player player, String rankOverride) {
        Optional<String> canonical = rankService.findCanonicalRank(rankOverride);
        if (canonical.isEmpty()) {
            return false;
        }
        NickProfile profile = repository.find(player.getUniqueId())
                .orElse(new NickProfile(player.getUniqueId(), null, null, null, Instant.now()));
        repository.save(profile.withRankOverride(canonical.get()));
        applyDisplay(player);
        return true;
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
        return resolved == null || resolved.isBlank() ? player.getName() : resolved;
    }

    private DisplayState resolveDisplay(Player player, Optional<NickProfile> profile) {
        String effectiveName = profile.map(NickProfile::nickname)
                .filter(s -> !s.isBlank())
                .orElse(player.getName());
        String effectiveRank = profile.map(NickProfile::rankOverride)
                .filter(s -> !s.isBlank())
                .orElse(rankService.resolveBaseRank(player));
        return new DisplayState(effectiveName, effectiveRank);
    }

    public DisplayState resolveStoredDisplay(UUID uuid, String realNameFallback) {
        Optional<NickProfile> profile = repository.find(uuid);
        String effectiveName = profile.map(NickProfile::nickname)
                .filter(s -> !s.isBlank())
                .orElse(realNameFallback == null ? "" : realNameFallback);
        String effectiveRank = profile.map(NickProfile::rankOverride)
                .filter(s -> !s.isBlank())
                .orElse("default");
        return new DisplayState(effectiveName, effectiveRank);
    }

    public Optional<NickProfile> findProfile(UUID uuid) {
        return repository.find(uuid);
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
                .map(Player::getName)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public boolean conflictsWithOnlineRealName(Player self, String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return false;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(nickname)) {
                return true;
            }
        }
        return self != null && self.getName().equalsIgnoreCase(nickname);
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
            packetName = player.getName();
        }

        boolean skinEnabled = plugin.getConfig().getBoolean("nick.skin.enabled", false);
        String customSkin = profile.map(NickProfile::skinName).orElse(null);
        SkinSource skinSource = customSkin == null || customSkin.isBlank()
                ? resolveDefaultSkinSource(player, state.effectiveName())
                : parseSkinSource(customSkin);
        if (skinEnabled && skinSource == null) {
            skinSource = SkinSource.forMojangName(player.getName());
        }
        String skinSourceKey = skinSource == null ? null : skinSource.sourceKey();
        String current = appliedSkinSource.get(player.getUniqueId());
        String currentPacketName = appliedPacketName.get(player.getUniqueId());
        if ((!skinEnabled || (skinSourceKey != null && skinSourceKey.equalsIgnoreCase(current)))
                && packetName.equalsIgnoreCase(currentPacketName == null ? "" : currentPacketName)) {
            return;
        }

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

        String skinName = skinSource.mojangName();
        com.destroystokyo.paper.profile.PlayerProfile lookup = Bukkit.createProfile(skinName);
        final String finalSkinName = skinSource.sourceKey();
        final String finalPacketName = packetName;
        lookup.update().thenAccept(updated -> Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(player.getUniqueId());
            if (online == null || !online.isOnline() || !isNicked(online)) {
                return;
            }
            applyProfile(online, finalPacketName, updated);
            appliedSkinSource.put(online.getUniqueId(), finalSkinName);
            appliedPacketName.put(online.getUniqueId(), finalPacketName);
        })).exceptionally(error -> {
            plugin.getLogger().warning("Failed to update skin for " + player.getName() + " via source " + finalSkinName);
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
        String realName = player.getName();
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
                    Bukkit.createProfile(online.getUniqueId(), online.getName());
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
            raw = player.getName();
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
            return SkinSource.forMojangName(player.getName());
        }
        return null;
    }

    private String resolvePacketName(Player player, String effectiveName) {
        if (!plugin.getConfig().getBoolean("nick.packet-name.enabled", true)) {
            return player.getName();
        }
        String candidate = sanitizeSkinName(effectiveName);
        if (candidate != null) {
            return candidate;
        }
        if (plugin.getConfig().getBoolean("nick.packet-name.fallback-to-real", true)) {
            return player.getName();
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

    private URL parseSkinUrl(String raw) {
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
