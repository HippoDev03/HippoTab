package net.mwtw.hippoTab.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.mwtw.hippoTab.service.PlaceholderService;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

public final class TabTextFormatter {
    private final PlaceholderService placeholderService;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public TabTextFormatter(PlaceholderService placeholderService) {
        this.placeholderService = placeholderService;
    }

    public Component toComponent(Player player, String text) {
        return miniMessage.deserialize(toMiniMessageText(player, text));
    }

    public Component linesToComponent(Player player, List<String> lines) {
        String joined = lines.stream()
            .map(line -> placeholderService.apply(player, line))
            .collect(Collectors.joining("\n"));
        return miniMessage.deserialize(toMiniMessageText(joined));
    }

    public PlaceholderService getPlaceholderService() {
        return placeholderService;
    }

    public Component fromMiniMessage(String miniMessageText) {
        return miniMessage.deserialize(miniMessageText);
    }

    public String toMiniMessageText(Player player, String text) {
        String withPlaceholders = placeholderService.apply(player, text);
        return toMiniMessageText(withPlaceholders);
    }

    private String toMiniMessageText(String text) {
        String withColors = LegacyColorTranslator.toMiniMessage(text);
        return normalizeHexColorTags(withColors);
    }

    // Normalizes <#...> tags after placeholders:
    // - <##RRGGBB> -> <#RRGGBB>
    // - <#RRGGBB> stays as-is
    // - <#&c>/<#§c> -> <red> style named colors
    // - invalid/unresolved <#...> tags are removed so they don't render literally
    private String normalizeHexColorTags(String input) {
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if (current != '<' || i + 2 >= input.length() || input.charAt(i + 1) != '#') {
                out.append(current);
                continue;
            }

            int end = input.indexOf('>', i + 2);
            if (end < 0) {
                // Tolerate missing '>' in tags like "<#29A3DB" and still consume as a color tag.
                int tokenStart = i + 2;
                int tokenEnd = tokenStart;
                while (tokenEnd < input.length()) {
                    char ch = input.charAt(tokenEnd);
                    if (Character.isWhitespace(ch) || ch == '<' || ch == '>') {
                        break;
                    }
                    tokenEnd++;
                }
                String token = input.substring(tokenStart, tokenEnd).trim();
                if (token.startsWith("#")) {
                    token = token.substring(1);
                }
                if (isHexColor(token)) {
                    out.append("<#").append(token).append(">");
                    i = tokenEnd - 1;
                    continue;
                }
                if (token.length() == 2 && (token.charAt(0) == '&' || token.charAt(0) == '§')) {
                    String named = namedTagForLegacyCode(token.charAt(1));
                    if (named != null) {
                        out.append(named);
                        i = tokenEnd - 1;
                        continue;
                    }
                }
                out.append(current);
                continue;
            }

            String token = input.substring(i + 2, end).trim();
            if (token.startsWith("#")) {
                token = token.substring(1);
            }

            if (isHexColor(token)) {
                out.append("<#").append(token).append(">");
            } else if (token.length() == 2 && (token.charAt(0) == '&' || token.charAt(0) == '§')) {
                String named = namedTagForLegacyCode(token.charAt(1));
                if (named != null) {
                    out.append(named);
                }
            }
            i = end;
        }
        return out.toString();
    }

    private boolean isHexColor(String value) {
        if (value.length() != 6) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            boolean isDigit = character >= '0' && character <= '9';
            boolean isLower = character >= 'a' && character <= 'f';
            boolean isUpper = character >= 'A' && character <= 'F';
            if (!isDigit && !isLower && !isUpper) {
                return false;
            }
        }
        return true;
    }

    private String namedTagForLegacyCode(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            default -> null;
        };
    }
}
