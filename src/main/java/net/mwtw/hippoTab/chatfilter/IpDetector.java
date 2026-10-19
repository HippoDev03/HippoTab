package net.mwtw.hippoTab.chatfilter;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects IPv4/IPv6 addresses in chat text, including common obfuscation
 * attempts where the separators are swapped for other symbols.
 */
public final class IpDetector {
    private static final String IPV4_OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final String SEG = "[0-9A-Fa-f]{1,4}";

    /** Strict IPv4 with normal dots and an optional :port. */
    private static final Pattern IPV4 = Pattern.compile(
            "\\b" + IPV4_OCTET + "(?:\\." + IPV4_OCTET + "){3}(?::\\d{1,5})?\\b");

    /** Strict IPv6 (full, compressed "::", and embedded-IPv4 forms). */
    private static final Pattern IPV6 = Pattern.compile("(?i)(?:"
            + "(?:" + SEG + ":){7}" + SEG + "|"
            + "(?:" + SEG + ":){1,7}:|"
            + "(?:" + SEG + ":){1,6}:" + SEG + "|"
            + "(?:" + SEG + ":){1,5}(?::" + SEG + "){1,2}|"
            + "(?:" + SEG + ":){1,4}(?::" + SEG + "){1,3}|"
            + "(?:" + SEG + ":){1,3}(?::" + SEG + "){1,4}|"
            + "(?:" + SEG + ":){1,2}(?::" + SEG + "){1,5}|"
            + SEG + ":(?::" + SEG + "){1,6}|"
            + ":(?:(?::" + SEG + "){1,7}|:)|"
            + "::(?:ffff(?::0{1,4})?:)?(?:" + IPV4_OCTET + "\\.){3}" + IPV4_OCTET + "|"
            + "(?:" + SEG + ":){1,4}:(?:" + IPV4_OCTET + "\\.){3}" + IPV4_OCTET
            + ")");

    /**
     * A disguised dot. Only "dot-like" tokens are accepted (the literal dot is
     * here; full-width "．" and ideographic "。" are folded to "." by normalize,
     * and the word "dot"/Chinese "點/点"). We deliberately do NOT treat "_", "-",
     * "," or whitespace as separators, so player names like "1_2_3_4",
     * coordinates "100 64 200 50" and lists "1, 2, 3, 4" are not flagged.
     */
    private static final String SEP_CORE = "(?:dot|點|点|\\.)";

    /**
     * One IPv4 octet separator that has been disguised: {@link #SEP_CORE}
     * optionally wrapped in () [] {} and/or surrounding spaces, so "1 [ . ] 2"
     * and "1 dot 2" both match, but bare "1 2" does not.
     */
    private static final String LOOSE_SEP = "(?:\\s*(?:"
            + "\\(\\s*" + SEP_CORE + "\\s*\\)|"
            + "\\[\\s*" + SEP_CORE + "\\s*\\]|"
            + "\\{\\s*" + SEP_CORE + "\\s*\\}|"
            + SEP_CORE
            + ")\\s*)";

    /** Loose IPv4: four 0-255 octets joined by any disguised separator. */
    private static final Pattern IPV4_LOOSE = Pattern.compile(
            "(?i)(?<!\\w)" + IPV4_OCTET + "(?:" + LOOSE_SEP + IPV4_OCTET + "){3}");

    /** label(.label)*.tld — the trailing letters-only TLD is captured in group 1. */
    private static final Pattern DOMAIN = Pattern.compile(
            "(?i)\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+([a-z]{2,63})\\b");

    private IpDetector() {
    }

    /** Detects a plainly-written IPv4 or IPv6 address. */
    public static boolean containsIp(String message) {
        return message != null
                && (IPV4.matcher(message).find() || IPV6.matcher(message).find());
    }

    /** Like {@link #containsIp} but also catches obfuscated separators. */
    public static boolean containsObfuscatedIp(String message) {
        if (message == null) {
            return false;
        }
        String normalized = normalize(message);
        return IPV4_LOOSE.matcher(normalized).find() || IPV6.matcher(normalized).find();
    }

    /**
     * Compiles a specific target address (e.g. "1.2.3.4" or "play.example.com")
     * into a pattern that still matches obfuscated forms: the dots may be written
     * as ".", "dot", "[.]", full-width, etc. Returns {@code null} for blank input.
     */
    public static Pattern compileTarget(String target) {
        if (target == null) {
            return null;
        }
        String trimmed = target.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] parts = trimmed.split("\\.", -1);
        StringBuilder sb = new StringBuilder("(?i)(?<!\\w)");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(LOOSE_SEP);
            }
            sb.append(Pattern.quote(parts[i]));
        }
        sb.append("(?!\\w)");
        return Pattern.compile(sb.toString());
    }

    /** True if the message contains any of the targeted addresses (obfuscation-tolerant). */
    public static boolean matchesAnyTarget(String message, java.util.List<Pattern> targets) {
        if (message == null || targets == null || targets.isEmpty()) {
            return false;
        }
        String text = normalize(message);
        for (Pattern target : targets) {
            if (target.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /** Detects a plainly-written domain whose TLD is in {@code tlds} (lower-case, no leading dot). */
    public static boolean containsDomain(String message, Set<String> tlds) {
        return findDomain(message, tlds);
    }

    /** Like {@link #containsDomain} but first de-obfuscates "x dot y", "x[.]y", etc. */
    public static boolean containsObfuscatedDomain(String message, Set<String> tlds) {
        if (message == null) {
            return false;
        }
        return findDomain(deobfuscateDots(normalize(message)), tlds);
    }

    private static boolean findDomain(String text, Set<String> tlds) {
        if (text == null || tlds == null || tlds.isEmpty()) {
            return false;
        }
        Matcher m = DOMAIN.matcher(text);
        while (m.find()) {
            if (tlds.contains(m.group(1).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Turns disguised dots into real ones so {@link #DOMAIN} can match:
     * bracketed forms "[.]"/"[dot]", the word "dot", and the Chinese "點/点".
     * Surrounding spaces are consumed so "play dot com" -> "play.com", but a
     * bare "play . com" (literal dot with spaces, no token) is left untouched
     * to avoid splitting normal sentences like "there. org meeting".
     */
    private static String deobfuscateDots(String s) {
        String r = s.replaceAll("(?i)\\s*[\\[({]\\s*(?:dot|點|点|\\.)\\s*[\\])}]\\s*", ".");
        r = r.replaceAll("(?i)\\s*\\bdot\\b\\s*", ".");
        r = r.replaceAll("\\s*[點点]\\s*", ".");
        return r;
    }

    /** Folds full-width ASCII variants (letters, digits, dots, colons) down to ASCII. */
    private static String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '！' && c <= '～') {    // full-width '!'..'~' -> ASCII
                sb.append((char) (c - 0xFEE0));
            } else if (c == '。') {                  // ideographic full stop -> '.'
                sb.append('.');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
