package net.mwtw.hippoTab.text;

import java.util.HashMap;
import java.util.Map;

public final class LegacyColorTranslator {
    private static final Map<Character, String> LEGACY_TO_MINI = new HashMap<>();

    static {
        LEGACY_TO_MINI.put('0', "<black>");
        LEGACY_TO_MINI.put('1', "<dark_blue>");
        LEGACY_TO_MINI.put('2', "<dark_green>");
        LEGACY_TO_MINI.put('3', "<dark_aqua>");
        LEGACY_TO_MINI.put('4', "<dark_red>");
        LEGACY_TO_MINI.put('5', "<dark_purple>");
        LEGACY_TO_MINI.put('6', "<gold>");
        LEGACY_TO_MINI.put('7', "<gray>");
        LEGACY_TO_MINI.put('8', "<dark_gray>");
        LEGACY_TO_MINI.put('9', "<blue>");
        LEGACY_TO_MINI.put('a', "<green>");
        LEGACY_TO_MINI.put('b', "<aqua>");
        LEGACY_TO_MINI.put('c', "<red>");
        LEGACY_TO_MINI.put('d', "<light_purple>");
        LEGACY_TO_MINI.put('e', "<yellow>");
        LEGACY_TO_MINI.put('f', "<white>");
        LEGACY_TO_MINI.put('k', "<obfuscated>");
        LEGACY_TO_MINI.put('l', "<bold>");
        LEGACY_TO_MINI.put('m', "<strikethrough>");
        LEGACY_TO_MINI.put('n', "<underlined>");
        LEGACY_TO_MINI.put('o', "<italic>");
        LEGACY_TO_MINI.put('r', "<reset>");
    }

    private LegacyColorTranslator() {
    }

    public static String toMiniMessage(String input) {
        StringBuilder out = new StringBuilder(input.length() + 16);

        for (int i = 0; i < input.length(); i++) {
            char current = input.charAt(i);
            if (!isLegacyPrefix(current) || i + 1 >= input.length()) {
                out.append(current);
                continue;
            }

            char prefix = current;
            char code = Character.toLowerCase(input.charAt(i + 1));

            if (code == '#' && i + 7 < input.length()) {
                String hex = input.substring(i + 2, i + 8);
                if (hex.matches("[A-Fa-f0-9]{6}")) {
                    out.append("<#").append(hex).append(">");
                    i += 7;
                    continue;
                }
            }

            if (code == 'x' && i + 13 < input.length()) {
                StringBuilder hex = new StringBuilder(6);
                boolean valid = true;
                for (int idx = 0; idx < 6; idx++) {
                    int ampIndex = i + 2 + (idx * 2);
                    if (input.charAt(ampIndex) != prefix) {
                        valid = false;
                        break;
                    }
                    char hexChar = input.charAt(ampIndex + 1);
                    if (!isHex(hexChar)) {
                        valid = false;
                        break;
                    }
                    hex.append(hexChar);
                }

                if (valid) {
                    out.append("<#").append(hex).append(">");
                    i += 13;
                    continue;
                }
            }

            String replacement = LEGACY_TO_MINI.get(code);
            if (replacement != null) {
                out.append(replacement);
                i++;
            } else {
                out.append(current);
            }
        }

        return out.toString();
    }

    private static boolean isLegacyPrefix(char character) {
        return character == '&' || character == '§';
    }

    private static boolean isHex(char character) {
        return (character >= '0' && character <= '9')
            || (character >= 'a' && character <= 'f')
            || (character >= 'A' && character <= 'F');
    }
}
