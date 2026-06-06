package net.mwtw.hippoTab.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mwtw.hippoTab.config.TabConfig;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NameTagService {
    private static final String PLAYER_NAME_TOKEN = "%player_name%";
    private final JavaPlugin plugin;
    private final TabConfig config;
    private final TabTextFormatter formatter;
    private final ConditionParser conditionParser;
    private BukkitTask updateTask;
    private Runnable onTeamsChanged;

    // player UUID → team name currently assigned on clients
    private final Map<UUID, String> playerTeams = new ConcurrentHashMap<>();
    // team name → last sent state (prefix/suffix/color)
    private final Map<String, TeamState> teamStates = new ConcurrentHashMap<>();
    // viewer UUID → set of team names this viewer has received CREATE for
    // Used to prevent duplicate CREATE or premature UPDATE (protocol errors)
    private final Map<UUID, Set<String>> viewerKnownTeams = new ConcurrentHashMap<>();
    // change detection
    private final Map<UUID, NameTagParts> cachedParts = new ConcurrentHashMap<>();
    private final Map<UUID, String> cachedUsername = new ConcurrentHashMap<>();

    public void setOnTeamsChanged(Runnable callback) {
        this.onTeamsChanged = callback;
    }

    public NameTagService(JavaPlugin plugin, TabConfig config, TabTextFormatter formatter, ConditionParser conditionParser) {
        this.plugin = plugin;
        this.config = config;
        this.formatter = formatter;
        this.conditionParser = conditionParser;
    }

    public void start() {
        if (!config.nametagEnabled()) {
            return;
        }
        if (config.nametagReplaceUsernameEnabled() && !isPacketEventsAvailable()) {
            plugin.getLogger().warning("nametag.replace-username.enabled=true but PacketEvents is not available. Skipping username replacement.");
        }
        updateAll();
        updateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            this::updateAll,
            config.nametagUpdateIntervalTicks(),
            config.nametagUpdateIntervalTicks()
        );
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        for (String teamName : teamStates.keySet()) {
            sendRawToAll(new WrapperPlayServerTeams(teamName, WrapperPlayServerTeams.TeamMode.REMOVE, Optional.empty()));
        }
        playerTeams.clear();
        teamStates.clear();
        viewerKnownTeams.clear();
        cachedParts.clear();
        cachedUsername.clear();
    }

    public void updateAll() {
        if (!config.nametagEnabled()) {
            return;
        }

        List<Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        long interval = config.nametagUpdateIntervalTicks();
        long spread = Math.min(players.size(), Math.min(5, Math.max(1, interval / 2)));

        long maxDelay = 0;
        for (int i = 0; i < players.size(); i++) {
            final Player p = players.get(i);
            long delay = i % spread;
            if (delay > maxDelay) maxDelay = delay;
            if (delay == 0) {
                updatePlayer(p);
            } else {
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                    if (p.isOnline()) updatePlayer(p);
                }, delay);
            }
        }

        if (onTeamsChanged != null) {
            if (maxDelay == 0) {
                onTeamsChanged.run();
            } else {
                final Runnable cb = onTeamsChanged;
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, cb, maxDelay + 1);
            }
        }
    }

    public void updateAllNextTick() {
        if (!config.nametagEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::updateAll, 1L);
    }

    public void updatePlayer(Player player) {
        if (!config.nametagEnabled()) {
            return;
        }

        if (!conditionParser.evaluate(player, config.nametagDisableIf())) {
            removePlayerTag(player);
            return;
        }

        NameTagParts nameTagParts = resolveNameTagParts(player);
        NameTagParts prevParts = cachedParts.get(player.getUniqueId());
        boolean partsChanged = !Objects.equals(prevParts, nameTagParts);
        boolean alreadyAssigned = playerTeams.containsKey(player.getUniqueId());

        if (partsChanged || !alreadyAssigned) {
            cachedParts.put(player.getUniqueId(), nameTagParts);

            NamedTextColor prefixColor = parseNamedColor(nameTagParts.rawPrefix());
            String visiblePrefix = stripTrailingNameColorTokens(nameTagParts.rawPrefix());
            Component prefix = formatter.fromMiniMessage(visiblePrefix);
            Component suffix = formatter.fromMiniMessage(nameTagParts.rawSuffix());
            NamedTextColor color = prefixColor != null ? prefixColor : NamedTextColor.WHITE;

            String teamName = getTeamName(player);
            TeamState state = new TeamState(prefix, suffix, color);
            teamStates.put(teamName, state);

            if (!alreadyAssigned) {
                playerTeams.put(player.getUniqueId(), teamName);
                // Send CREATE or UPDATE depending on whether each viewer already knows this team
                sendTeamToAllViewers(teamName, state, List.of(player.getName()));
            } else {
                // Parts changed — send UPDATE (or CREATE if viewer somehow missed it)
                sendTeamToAllViewers(teamName, state, List.of(player.getName()));
            }
        }

        applyUsernameReplacement(player, true);
    }

    // Called on join: sends CREATE for all existing teams to the new viewer.
    // Uses the same known-teams check so there is no duplicate CREATE if updatePlayer
    // races with this method.
    public void onPlayerJoin(Player joiner) {
        if (!config.nametagEnabled()) {
            return;
        }
        for (Map.Entry<String, TeamState> entry : teamStates.entrySet()) {
            String teamName = entry.getKey();
            String memberName = getMemberNameForTeam(teamName);
            List<String> members = memberName != null ? List.of(memberName) : Collections.emptyList();
            sendTeamToViewer(joiner, teamName, entry.getValue(), members);
        }
    }

    public void removePlayer(Player player) {
        // Drop this player's viewer state — they are leaving
        viewerKnownTeams.remove(player.getUniqueId());
        cachedParts.remove(player.getUniqueId());
        cachedUsername.remove(player.getUniqueId());

        String teamName = playerTeams.remove(player.getUniqueId());
        if (teamName != null) {
            teamStates.remove(teamName);
            removeTeamFromAllViewers(teamName, player.getName());
        }
        applyUsernameReplacement(player, false);
        player.customName(null);
        player.setCustomNameVisible(false);
    }

    private void removePlayerTag(Player player) {
        cachedParts.remove(player.getUniqueId());
        String teamName = playerTeams.get(player.getUniqueId());
        if (teamName != null) {
            TeamState resetState = new TeamState(Component.empty(), Component.empty(), NamedTextColor.WHITE);
            teamStates.put(teamName, resetState);
            sendTeamToAllViewers(teamName, resetState, List.of(player.getName()));
        }
        applyUsernameReplacement(player, false);
        player.customName(null);
        player.setCustomNameVisible(false);
    }

    public String getFormattedName(Player player) {
        NameTagParts parts = resolveNameTagParts(player);
        return stripTrailingNameColorTokens(parts.rawPrefix()) + player.getName() + parts.rawSuffix();
    }

    public String getFormattedPrefix(Player player) {
        return stripTrailingNameColorTokens(resolveNameTagParts(player).rawPrefix());
    }

    public String getFormattedSuffix(Player player) {
        return resolveNameTagParts(player).rawSuffix();
    }

    public void pruneUnusedTeams() {
        // No-op: team state is fully managed via packets now
    }

    // -------------------------------------------------------------------------
    // Packet helpers — all team sends go through these to prevent protocol errors
    // -------------------------------------------------------------------------

    /**
     * Sends team info to a single viewer.
     * If the viewer already received CREATE for this team → sends UPDATE.
     * If not → sends CREATE (which also adds the member in one packet).
     */
    private void sendTeamToViewer(Player viewer, String teamName, TeamState state, List<String> members) {
        Set<String> known = viewerKnownTeams.computeIfAbsent(viewer.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        WrapperPlayServerTeams.ScoreBoardTeamInfo info = buildTeamInfo(state);
        if (known.add(teamName)) {
            sendTo(viewer, new WrapperPlayServerTeams(teamName, WrapperPlayServerTeams.TeamMode.CREATE, Optional.of(info), members));
        } else {
            sendTo(viewer, new WrapperPlayServerTeams(teamName, WrapperPlayServerTeams.TeamMode.UPDATE, Optional.of(info), Collections.emptyList()));
        }
    }

    /** Sends to all currently online viewers via {@link #sendTeamToViewer}. */
    private void sendTeamToAllViewers(String teamName, TeamState state, List<String> members) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            sendTeamToViewer(viewer, teamName, state, members);
        }
    }

    /** Sends REMOVE_ENTITIES + REMOVE only to viewers who have received CREATE for this team. */
    private void removeTeamFromAllViewers(String teamName, String playerName) {
        WrapperPlayServerTeams removeEntities = new WrapperPlayServerTeams(
            teamName, WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES, Optional.empty(), List.of(playerName));
        WrapperPlayServerTeams removeTeam = new WrapperPlayServerTeams(
            teamName, WrapperPlayServerTeams.TeamMode.REMOVE, Optional.empty(), Collections.emptyList());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Set<String> known = viewerKnownTeams.get(viewer.getUniqueId());
            if (known != null && known.remove(teamName)) {
                sendTo(viewer, removeEntities);
                sendTo(viewer, removeTeam);
            }
        }
    }

    private void sendRawToAll(WrapperPlayServerTeams packet) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            sendTo(viewer, packet);
        }
    }

    private void sendTo(Player viewer, WrapperPlayServerTeams packet) {
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, packet);
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Skipped team packet for " + viewer.getName() + ": " + e.getClass().getSimpleName());
        }
    }

    private String getMemberNameForTeam(String teamName) {
        for (Map.Entry<UUID, String> entry : playerTeams.entrySet()) {
            if (teamName.equals(entry.getValue())) {
                Player p = Bukkit.getPlayer(entry.getKey());
                return p != null ? p.getName() : null;
            }
        }
        return null;
    }

    private String getTeamName(Player player) {
        return "ht_" + player.getUniqueId().toString().substring(0, 12);
    }

    private WrapperPlayServerTeams.ScoreBoardTeamInfo buildTeamInfo(TeamState state) {
        return new WrapperPlayServerTeams.ScoreBoardTeamInfo(
            Component.empty(),
            state.prefix(),
            state.suffix(),
            WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
            WrapperPlayServerTeams.CollisionRule.NEVER,
            state.color(),
            WrapperPlayServerTeams.OptionData.NONE
        );
    }

    // -------------------------------------------------------------------------
    // Username replacement (PacketEvents entity metadata)
    // -------------------------------------------------------------------------

    private void applyUsernameReplacement(Player player, boolean enabled) {
        if (!config.nametagReplaceUsernameEnabled() || !isPacketEventsAvailable()) {
            return;
        }

        String renderedUsername = null;
        Component nameComponent = null;
        if (enabled) {
            String format = config.nametagReplaceUsernameFormat();
            if (format != null && !format.isBlank()) {
                renderedUsername = formatter.toMiniMessageText(player, format);
                if (!renderedUsername.isBlank()) {
                    nameComponent = formatter.fromMiniMessage(renderedUsername);
                } else {
                    renderedUsername = null;
                }
            }
        }

        String prevUsername = cachedUsername.get(player.getUniqueId());
        if (Objects.equals(renderedUsername, prevUsername)) {
            return;
        }
        cachedUsername.put(player.getUniqueId(), renderedUsername);

        String customNameJson = nameComponent == null ? null : AdventureSerializer.toJson(nameComponent);
        boolean hasCustomName = customNameJson != null && !customNameJson.isBlank();
        List<EntityData<?>> metadata = hasCustomName
            ? List.of(
                new EntityData<>(2, EntityDataTypes.OPTIONAL_COMPONENT, Optional.of(customNameJson)),
                new EntityData<>(3, EntityDataTypes.BOOLEAN, true)
            )
            : List.of(
                new EntityData<>(2, EntityDataTypes.OPTIONAL_COMPONENT, Optional.empty()),
                new EntityData<>(3, EntityDataTypes.BOOLEAN, false)
            );

        WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(player.getEntityId(), metadata);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.isOnline() || !viewer.isValid()) {
                continue;
            }
            try {
                PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, packet);
            } catch (RuntimeException e) {
                plugin.getLogger().fine(
                    "Skipped username replacement packet for viewer "
                        + viewer.getName()
                        + " due to transient channel state: "
                        + e.getClass().getSimpleName()
                );
            }
        }
    }

    private boolean isPacketEventsAvailable() {
        return plugin.getServer().getPluginManager().isPluginEnabled("packetevents");
    }

    // -------------------------------------------------------------------------
    // Name tag text resolution
    // -------------------------------------------------------------------------

    private NameTagParts resolveNameTagParts(Player player) {
        String rawFormat = config.nametagNameFormat();
        if (rawFormat == null || rawFormat.isBlank()) {
            rawFormat = config.nametagPrefix() + PLAYER_NAME_TOKEN + config.nametagSuffix();
        }

        int playerNameIndex = rawFormat.indexOf(PLAYER_NAME_TOKEN);
        if (playerNameIndex < 0) {
            return new NameTagParts(formatter.toMiniMessageText(player, rawFormat), "");
        }

        String rawPrefix = rawFormat.substring(0, playerNameIndex);
        String rawSuffix = rawFormat.substring(playerNameIndex + PLAYER_NAME_TOKEN.length())
            .replace(PLAYER_NAME_TOKEN, "");
        String prefix = formatter.toMiniMessageText(player, rawPrefix);
        String suffix = formatter.toMiniMessageText(player, rawSuffix);
        return new NameTagParts(prefix, suffix);
    }

    // -------------------------------------------------------------------------
    // Color parsing
    // -------------------------------------------------------------------------

    private NamedTextColor parseNamedColor(String processed) {
        NamedTextColor lastColor = null;

        for (int i = 0; i < processed.length(); i++) {
            char current = processed.charAt(i);

            if (current == '<') {
                int end = processed.indexOf('>', i + 1);
                if (end < 0) {
                    continue;
                }
                String token = processed.substring(i + 1, end);
                if (token.length() == 7 && token.charAt(0) == '#' && isHexColor(token.substring(1))) {
                    lastColor = findNearestNamedColor(token.substring(1));
                } else {
                    NamedTextColor namedColor = NamedTextColor.NAMES.value(token);
                    if (namedColor != null) {
                        lastColor = namedColor;
                    }
                }
                i = end;
                continue;
            }

            if (current != '&' && current != '§' || i + 1 >= processed.length()) {
                continue;
            }

            char code = Character.toLowerCase(processed.charAt(i + 1));

            if (code == '#' && i + 7 < processed.length()) {
                String hex = processed.substring(i + 2, i + 8);
                if (isHexColor(hex)) {
                    lastColor = findNearestNamedColor(hex);
                    i += 7;
                    continue;
                }
            }

            if (code == 'x' && i + 13 < processed.length()) {
                StringBuilder hex = new StringBuilder(6);
                boolean valid = true;
                for (int idx = 0; idx < 6; idx++) {
                    int markerIndex = i + 2 + (idx * 2);
                    if (processed.charAt(markerIndex) != current) {
                        valid = false;
                        break;
                    }
                    char hexChar = processed.charAt(markerIndex + 1);
                    if (!isHexChar(hexChar)) {
                        valid = false;
                        break;
                    }
                    hex.append(hexChar);
                }
                if (valid) {
                    lastColor = findNearestNamedColor(hex.toString());
                    i += 13;
                    continue;
                }
            }

            NamedTextColor legacyColor = getLegacyColor(code);
            if (legacyColor != null) {
                lastColor = legacyColor;
                i++;
            }
        }

        String trailingHex = extractTrailingRawHexToken(processed);
        if (trailingHex != null) {
            lastColor = findNearestNamedColor(trailingHex);
        }

        return lastColor;
    }

    private String stripTrailingNameColorTokens(String input) {
        String value = input;
        while (true) {
            int end = value.length();
            while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
                end--;
            }
            if (end == 0) {
                return value;
            }

            boolean removed = false;

            if (value.charAt(end - 1) == '>') {
                int start = value.lastIndexOf('<', end - 1);
                if (start >= 0) {
                    String token = value.substring(start + 1, end).trim();
                    if (isMiniMessageColorToken(token)) {
                        value = value.substring(0, start) + value.substring(end);
                        removed = true;
                    }
                }
            }

            if (!removed && end >= 2) {
                char marker = value.charAt(end - 2);
                char code = Character.toLowerCase(value.charAt(end - 1));
                if ((marker == '&' || marker == '§') && getLegacyColor(code) != null) {
                    value = value.substring(0, end - 2) + value.substring(end);
                    removed = true;
                }
            }

            if (!removed) {
                int start = trailingLegacyHexStart(value);
                if (start >= 0) {
                    value = value.substring(0, start);
                    removed = true;
                }
            }

            if (!removed) {
                String trailingHex = extractTrailingRawHexToken(value);
                if (trailingHex != null) {
                    int start = trailingRawHexStart(value);
                    if (start >= 0) {
                        value = value.substring(0, start);
                        removed = true;
                    }
                }
            }

            if (!removed) {
                return value;
            }
        }
    }

    private boolean isMiniMessageColorToken(String token) {
        if (token.isEmpty() || token.charAt(0) == '/') {
            return false;
        }
        if (token.equalsIgnoreCase("reset")) {
            return true;
        }
        if (token.charAt(0) == '#' && token.length() == 7 && isHexColor(token.substring(1))) {
            return true;
        }
        return NamedTextColor.NAMES.value(token) != null;
    }

    private boolean isHexColor(String value) {
        if (value.length() != 6) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!isHexChar(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean isHexChar(char character) {
        return (character >= '0' && character <= '9')
            || (character >= 'a' && character <= 'f')
            || (character >= 'A' && character <= 'F');
    }

    private String extractTrailingRawHexToken(String value) {
        int start = trailingRawHexStart(value);
        if (start < 0) {
            return null;
        }
        String token = value.substring(start).trim();
        if (token.startsWith("#")) {
            token = token.substring(1);
        }
        return isHexColor(token) ? token : null;
    }

    private int trailingRawHexStart(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        if (end == 0) {
            return -1;
        }

        int start = end;
        while (start > 0) {
            char c = value.charAt(start - 1);
            if (Character.isWhitespace(c)) {
                break;
            }
            start--;
        }

        String token = value.substring(start, end).trim();
        String normalized = token.startsWith("#") ? token.substring(1) : token;
        if (!isHexColor(normalized)) {
            return -1;
        }
        return start;
    }

    private int trailingLegacyHexStart(String value) {
        int end = trimmedEnd(value);
        if (end == 0) {
            return -1;
        }

        int start = Math.max(0, end - 8);
        if (end - start == 8) {
            char marker = value.charAt(start);
            char type = value.charAt(start + 1);
            if ((marker == '&' || marker == '§') && type == '#') {
                String hex = value.substring(start + 2, end);
                if (isHexColor(hex)) {
                    return start;
                }
            }
        }

        start = Math.max(0, end - 14);
        if (end - start == 14) {
            char marker = value.charAt(start);
            char type = Character.toLowerCase(value.charAt(start + 1));
            if ((marker == '&' || marker == '§') && type == 'x' && isLegacySplitHex(value, start, marker)) {
                return start;
            }
        }

        return -1;
    }

    private int trimmedEnd(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    private boolean isLegacySplitHex(String value, int start, char marker) {
        for (int idx = 0; idx < 6; idx++) {
            int markerIndex = start + 2 + (idx * 2);
            if (value.charAt(markerIndex) != marker || !isHexChar(value.charAt(markerIndex + 1))) {
                return false;
            }
        }
        return true;
    }

    private NamedTextColor findNearestNamedColor(String hex) {
        int r = Integer.parseInt(hex.substring(0, 2), 16);
        int g = Integer.parseInt(hex.substring(2, 4), 16);
        int b = Integer.parseInt(hex.substring(4, 6), 16);

        NamedTextColor closest = NamedTextColor.WHITE;
        double minDistance = Double.MAX_VALUE;
        for (NamedTextColor color : NamedTextColor.NAMES.values()) {
            double distance = Math.pow(r - color.red(), 2)
                + Math.pow(g - color.green(), 2)
                + Math.pow(b - color.blue(), 2);
            if (distance < minDistance) {
                minDistance = distance;
                closest = color;
            }
        }
        return closest;
    }

    private NamedTextColor getLegacyColor(char code) {
        return switch (code) {
            case '0' -> NamedTextColor.BLACK;
            case '1' -> NamedTextColor.DARK_BLUE;
            case '2' -> NamedTextColor.DARK_GREEN;
            case '3' -> NamedTextColor.DARK_AQUA;
            case '4' -> NamedTextColor.DARK_RED;
            case '5' -> NamedTextColor.DARK_PURPLE;
            case '6' -> NamedTextColor.GOLD;
            case '7' -> NamedTextColor.GRAY;
            case '8' -> NamedTextColor.DARK_GRAY;
            case '9' -> NamedTextColor.BLUE;
            case 'a' -> NamedTextColor.GREEN;
            case 'b' -> NamedTextColor.AQUA;
            case 'c' -> NamedTextColor.RED;
            case 'd' -> NamedTextColor.LIGHT_PURPLE;
            case 'e' -> NamedTextColor.YELLOW;
            case 'f' -> NamedTextColor.WHITE;
            default -> null;
        };
    }

    public void cleanup() {
        stop();
    }

    // -------------------------------------------------------------------------
    // Records
    // -------------------------------------------------------------------------

    private record NameTagParts(String rawPrefix, String rawSuffix) {}

    private record TeamState(Component prefix, Component suffix, NamedTextColor color) {}
}
