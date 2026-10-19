package net.mwtw.hippoTab.chatfilter;

import com.github.houbb.opencc4j.util.ZhConverterUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds filter words in a chat message in a single pass (Aho-Corasick), however long
 * the word list is.
 *
 * <p>Both the words and the message are first <em>folded</em>: full-width characters
 * become ASCII, letters lower-case, Traditional Chinese becomes Simplified, and
 * everything that is not a letter or digit is dropped. So one stored word matches every
 * script/case variant, and separators typed between the characters of a word
 * ("f u c k", "幹.你") no longer hide it. Each folded character remembers where it came
 * from, so the censoring is applied to the original text.</p>
 */
public final class WordMatcher {
    /** Most separator characters tolerated between two characters of one word. */
    private static final int MAX_GAP = 4;
    // Traditional -> Simplified per character, filled lazily (0 = not looked up yet).
    private static final char[] SIMPLIFIED = new char[Character.MAX_VALUE + 1];

    /** What a scan found: the censored text and the original substrings that were censored. */
    public record Result(String censored, List<String> hits) {
        public boolean matched() {
            return !hits.isEmpty();
        }
    }

    private final boolean ignoreSeparators;
    private final int wordCount;
    // Trie transitions keyed by (node << 16 | char); node 0 is the root.
    private final HashMap<Long, Integer> next = new HashMap<>();
    private int[] fail = new int[64];
    // Nearest proper suffix node at which a word ends (0 = none).
    private int[] link = new int[64];
    // Length of the filter word / allowed phrase ending at a node (0 = none).
    private int[] badLength = new int[64];
    private int[] allowLength = new int[64];
    // Whether the filter word ending at a node is plain ASCII letters/digits.
    private boolean[] badAscii = new boolean[64];
    private int[] depth = new int[64];
    private int nodeCount = 1;

    /**
     * @param words            filter words, in any script/case
     * @param allowed          phrases that are never censored; a filter word found inside
     *                         one is left alone, and an exact entry disables that word
     * @param ignoreSeparators whether separators between a word's characters are skipped
     */
    public WordMatcher(Collection<String> words, Collection<String> allowed, boolean ignoreSeparators) {
        this.ignoreSeparators = ignoreSeparators;

        Set<String> allowedFolded = new LinkedHashSet<>();
        for (String phrase : allowed) {
            String folded = fold(phrase);
            if (!folded.isEmpty()) {
                allowedFolded.add(folded);
            }
        }
        Map<String, Boolean> badFolded = new LinkedHashMap<>();
        for (String word : words) {
            String folded = fold(word);
            if (!folded.isEmpty() && !allowedFolded.contains(folded)) {
                badFolded.put(folded, isAsciiWord(folded));
            }
        }

        for (Map.Entry<String, Boolean> entry : badFolded.entrySet()) {
            int node = insert(entry.getKey());
            badLength[node] = entry.getKey().length();
            badAscii[node] = entry.getValue();
        }
        for (String phrase : allowedFolded) {
            allowLength[insert(phrase)] = phrase.length();
        }
        this.wordCount = badFolded.size();
        buildLinks();
    }

    public int wordCount() {
        return wordCount;
    }

    public Result scan(String text) {
        if (text == null || text.isEmpty() || wordCount == 0) {
            return new Result(text == null ? "" : text, List.of());
        }
        int length = text.length();
        char[] folded = new char[length];
        int[] origin = new int[length];
        int count = 0;
        for (int i = 0; i < length; i++) {
            char c = foldChar(text.charAt(i));
            if (isSignificant(c)) {
                folded[count] = c;
                origin[count] = i;
                count++;
            }
        }

        List<int[]> bad = new ArrayList<>();
        List<int[]> allowed = new ArrayList<>();
        int node = 0;
        for (int i = 0; i < count; i++) {
            char c = folded[i];
            while (true) {
                Integer target = next.get(key(node, c));
                if (target != null) {
                    node = target;
                    break;
                }
                if (node == 0) {
                    break;
                }
                node = fail[node];
            }
            int ending = badLength[node] > 0 || allowLength[node] > 0 ? node : link[node];
            for (; ending != 0; ending = link[ending]) {
                if (badLength[ending] > 0) {
                    int start = i - badLength[ending] + 1;
                    if (accept(folded, origin, count, start, i, badAscii[ending])) {
                        bad.add(new int[] {start, i});
                    }
                }
                if (allowLength[ending] > 0) {
                    int start = i - allowLength[ending] + 1;
                    if (contiguous(origin, start, i)) {
                        allowed.add(new int[] {start, i});
                    }
                }
            }
        }
        if (bad.isEmpty()) {
            return new Result(text, List.of());
        }

        char[] out = text.toCharArray();
        Set<String> hits = new LinkedHashSet<>();
        for (int[] span : bad) {
            if (insideAllowed(span, allowed)) {
                continue;
            }
            int from = origin[span[0]];
            int to = origin[span[1]];
            hits.add(text.substring(from, to + 1));
            for (int i = from; i <= to; i++) {
                out[i] = '*';
            }
        }
        if (hits.isEmpty()) {
            return new Result(text, List.of());
        }
        return new Result(new String(out), List.copyOf(hits));
    }

    /**
     * Whether a folded occurrence is a real hit in the original text.
     * ASCII words keep word boundaries (so "ass" is not found in "class"), and may only
     * be split by separators when EVERY character is split off ("a s s" yes; "he'll" as
     * "hell" or "an al" as "anal" no). Other scripts have no word boundaries and may be
     * split anywhere, by at most {@link #MAX_GAP} separators.
     */
    private boolean accept(char[] folded, int[] origin, int count, int start, int end, boolean ascii) {
        boolean anyGap = false;
        boolean allGaps = true;
        for (int i = start; i < end; i++) {
            int gap = origin[i + 1] - origin[i] - 1;
            if (gap > MAX_GAP) {
                return false;
            }
            if (gap > 0) {
                anyGap = true;
            } else {
                allGaps = false;
            }
        }
        if (anyGap && !ignoreSeparators) {
            return false;
        }
        if (!ascii) {
            return true;
        }
        if (anyGap && !allGaps) {
            return false;
        }
        if (start > 0 && origin[start - 1] == origin[start] - 1 && isAsciiAlnum(folded[start - 1])) {
            return false;
        }
        return end + 1 >= count || origin[end + 1] != origin[end] + 1 || !isAsciiAlnum(folded[end + 1]);
    }

    private static boolean contiguous(int[] origin, int start, int end) {
        return origin[end] - origin[start] == end - start;
    }

    private static boolean insideAllowed(int[] span, List<int[]> allowed) {
        for (int[] range : allowed) {
            if (range[0] <= span[0] && span[1] <= range[1]) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Folding
    // -------------------------------------------------------------------------

    /** Folds text to the comparison form: folded characters with every separator removed. */
    public static String fold(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = foldChar(text.charAt(i));
            if (isSignificant(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static char foldChar(char c) {
        if (c >= '！' && c <= '～') {
            c = (char) (c - 0xFEE0);
        }
        c = Character.toLowerCase(c);
        boolean han = (c >= 0x3400 && c <= 0x9FFF) || (c >= 0xF900 && c <= 0xFAFF);
        return han ? simplified(c) : c;
    }

    private static char simplified(char c) {
        char cached = SIMPLIFIED[c];
        if (cached != 0) {
            return cached;
        }
        char result = c;
        try {
            String converted = ZhConverterUtil.toSimple(String.valueOf(c));
            if (converted != null && converted.length() == 1) {
                result = converted.charAt(0);
            }
        } catch (RuntimeException ignored) {
            // Keep the character as typed.
        }
        SIMPLIFIED[c] = result;
        return result;
    }

    private static boolean isSignificant(char c) {
        return Character.isLetterOrDigit(c) || Character.isSurrogate(c);
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static boolean isAsciiWord(String folded) {
        for (int i = 0; i < folded.length(); i++) {
            if (!isAsciiAlnum(folded.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Automaton construction
    // -------------------------------------------------------------------------

    private static long key(int node, char c) {
        return ((long) node << 16) | c;
    }

    private int insert(String folded) {
        int node = 0;
        for (int i = 0; i < folded.length(); i++) {
            long key = key(node, folded.charAt(i));
            Integer target = next.get(key);
            if (target == null) {
                target = newNode(depth[node] + 1);
                next.put(key, target);
            }
            node = target;
        }
        return node;
    }

    private int newNode(int nodeDepth) {
        if (nodeCount == fail.length) {
            int size = nodeCount * 2;
            fail = java.util.Arrays.copyOf(fail, size);
            link = java.util.Arrays.copyOf(link, size);
            badLength = java.util.Arrays.copyOf(badLength, size);
            allowLength = java.util.Arrays.copyOf(allowLength, size);
            badAscii = java.util.Arrays.copyOf(badAscii, size);
            depth = java.util.Arrays.copyOf(depth, size);
        }
        depth[nodeCount] = nodeDepth;
        return nodeCount++;
    }

    private void buildLinks() {
        // Failure links need the shallower nodes done first: process edges by depth.
        List<long[]> edges = new ArrayList<>(next.size());
        for (Map.Entry<Long, Integer> entry : next.entrySet()) {
            edges.add(new long[] {entry.getKey(), entry.getValue()});
        }
        edges.sort((a, b) -> Integer.compare(depth[(int) a[1]], depth[(int) b[1]]));
        for (long[] edge : edges) {
            int parent = (int) (edge[0] >>> 16);
            char c = (char) (edge[0] & 0xFFFF);
            int node = (int) edge[1];
            int target = 0;
            if (parent != 0) {
                int candidate = fail[parent];
                while (true) {
                    Integer found = next.get(key(candidate, c));
                    if (found != null) {
                        target = found;
                        break;
                    }
                    if (candidate == 0) {
                        break;
                    }
                    candidate = fail[candidate];
                }
            }
            fail[node] = target;
            link[node] = badLength[target] > 0 || allowLength[target] > 0 ? target : link[target];
        }
    }
}
