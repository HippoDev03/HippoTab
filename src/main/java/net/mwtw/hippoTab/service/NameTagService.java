package net.mwtw.hippoTab.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mwtw.hippoTab.config.TabConfig;
import net.mwtw.hippoTab.text.TabTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public final class NameTagService {
    private final JavaPlugin plugin;
    private final TabConfig config;
    private final TabTextFormatter formatter;
    private final ConditionParser conditionParser;
    private Scoreboard scoreboard;
    private BukkitTask updateTask;

    public NameTagService(JavaPlugin plugin, TabConfig config, TabTextFormatter formatter, ConditionParser conditionParser) {
        this.plugin = plugin;
        this.config = config;
        this.formatter = formatter;
        this.conditionParser = conditionParser;
    }

    private Scoreboard getScoreboard() {
        if (scoreboard == null) {
            scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        }
        return scoreboard;
    }

    public void start() {
        if (!config.nametagEnabled()) {
            return;
        }
        
        updateAll();
        updateTask = Bukkit.getScheduler().runTaskTimer(
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
    }

    public void updateAll() {
        if (!config.nametagEnabled()) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayer(player);
        }
    }

    public void updatePlayer(Player player) {
        if (!config.nametagEnabled()) {
            return;
        }

        if (!conditionParser.evaluate(player, config.nametagDisableIf())) {
            removePlayerTag(player);
            return;
        }

        Team team = getOrCreateTeam(player);

        String resolvedPrefix = formatter.toMiniMessageText(player, config.nametagPrefix());
        NamedTextColor prefixColor = parseNamedColor(resolvedPrefix);
        String visiblePrefix = stripTrailingNameColorTokens(resolvedPrefix);

        // Build prefix and suffix components
        Component prefix = formatter.fromMiniMessage(visiblePrefix);
        Component suffix = formatter.toComponent(player, config.nametagSuffix());
        
        team.prefix(prefix);
        team.suffix(suffix);
        
        // Determine the player name color
        // Priority: 1) Last color from prefix, 2) WHITE fallback
        NamedTextColor teamColor = null;
        
        if (prefixColor != null) {
            teamColor = prefixColor;
        }
        
        // Final fallback to WHITE
        if (teamColor == null) {
            teamColor = NamedTextColor.WHITE;
        }
        
        // Apply team color to the player name part
        team.color(teamColor);
        
        // Ensure name tags are visible
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
        
        // Only send an add when the scoreboard is not already mapped to this team.
        if (!isCurrentTeamMember(team, player)) {
            team.addEntity(player);
        }
    }

    public String getFormattedName(Player player) {
        String prefix = getVisiblePrefixText(player);
        String suffix = formatter.getPlaceholderService().apply(player, config.nametagSuffix());
        return prefix + player.getName() + suffix;
    }

    public String getFormattedPrefix(Player player) {
        return getVisiblePrefixText(player);
    }

    public String getFormattedSuffix(Player player) {
        return formatter.getPlaceholderService().apply(player, config.nametagSuffix());
    }

    private String getVisiblePrefixText(Player player) {
        String resolvedPrefix = formatter.toMiniMessageText(player, config.nametagPrefix());
        return stripTrailingNameColorTokens(resolvedPrefix);
    }

    private NamedTextColor parseNamedColor(String processed) {
        NamedTextColor lastColor = null;

        for (int i = 0; i < processed.length(); i++) {
            char current = processed.charAt(i);

            // Parse MiniMessage-style colors first (these come from PAPI or converted legacy codes)
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

            // &#RRGGBB or §#RRGGBB (parse these before legacy codes)
            if (code == '#' && i + 7 < processed.length()) {
                String hex = processed.substring(i + 2, i + 8);
                if (isHexColor(hex)) {
                    lastColor = findNearestNamedColor(hex);
                    i += 7;
                    continue;
                }
            }

            // &x&F&F&0&0&0&0 / §x§F§F§0§0§0§0 (parse these before legacy codes)
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

            // Legacy color codes (parsed last)
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

    // Removes trailing color-only markers used to color the player name (e.g. "<#29A3DB>", "<red>", "&c").
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
        // Convert hex to RGB
        int r = Integer.parseInt(hex.substring(0, 2), 16);
        int g = Integer.parseInt(hex.substring(2, 4), 16);
        int b = Integer.parseInt(hex.substring(4, 6), 16);
        
        return findNearestNamedColorFromRGB(r, g, b);
    }
    
    private NamedTextColor findNearestNamedColorFromRGB(int r, int g, int b) {
        // Find closest named color (simple distance calculation)
        NamedTextColor closest = NamedTextColor.WHITE;
        double minDistance = Double.MAX_VALUE;
        
        for (NamedTextColor color : NamedTextColor.NAMES.values()) {
            int cr = color.red();
            int cg = color.green();
            int cb = color.blue();
            
            double distance = Math.sqrt(
                Math.pow(r - cr, 2) + 
                Math.pow(g - cg, 2) + 
                Math.pow(b - cb, 2)
            );
            
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

    public void removePlayer(Player player) {
        Scoreboard sb = getScoreboard();
        Team team = sb.getTeam(getTeamName(player));
        if (team != null && isCurrentTeamMember(team, player)) {
            team.removeEntity(player);
        }
        // Reset custom name
        player.customName(null);
        player.setCustomNameVisible(false);
    }

    private void removePlayerTag(Player player) {
        String teamName = getTeamName(player);
        Scoreboard sb = getScoreboard();
        Team team = sb.getTeam(teamName);
        if (team != null) {
            team.prefix(Component.empty());
            team.suffix(Component.empty());
            team.color(NamedTextColor.WHITE);
        }
        // Reset custom name
        player.customName(null);
        player.setCustomNameVisible(false);
    }

    private Team getOrCreateTeam(Player player) {
        String teamName = getTeamName(player);
        Scoreboard sb = getScoreboard();
        Team team = sb.getTeam(teamName);
        if (team == null) {
            team = sb.registerNewTeam(teamName);
        }
        return team;
    }

    private String getTeamName(Player player) {
        return "ht_" + player.getUniqueId().toString().substring(0, 12);
    }

    private boolean isCurrentTeamMember(Team team, Player player) {
        return getScoreboard().getEntityTeam(player) == team;
    }

    public void cleanup() {
        stop();
        for (Player player : Bukkit.getOnlinePlayers()) {
            removePlayer(player);
        }
    }
}
