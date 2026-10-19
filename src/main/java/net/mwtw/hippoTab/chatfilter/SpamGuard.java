package net.mwtw.hippoTab.chatfilter;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player anti-spam state for chat and the watched commands: a rate limit (how fast),
 * a duplicate limit (the same or nearly the same text again), and an escalating mute for
 * players who keep tripping either one.
 *
 * <p>The state is NOT dropped when a player disconnects — reconnecting must not wipe a
 * mute or the counters. Idle entries are purged after a while instead.</p>
 */
public final class SpamGuard {
    private static final long PURGE_INTERVAL_MILLIS = 5 * 60_000L;
    // Long enough for any mute or strike count in practical use to have run out.
    private static final long IDLE_RETENTION_MILLIS = 15 * 60_000L;
    // Comparing is quadratic in the length; chat lines are capped at 256 by the client.
    private static final int MAX_COMPARE_LENGTH = 256;

    public enum Verdict {
        ALLOW,
        /** Sending too fast. */
        RATE,
        /** Same (or nearly the same) text as recent messages. */
        DUPLICATE,
        /** Currently muted for earlier spam; see {@link Result#muteSecondsLeft()}. */
        MUTED
    }

    /**
     * @param muteSecondsLeft above 0 when the player is muted now — either from before
     *                        or as the penalty for this very message
     */
    public record Result(Verdict verdict, long muteSecondsLeft) {
        public boolean blocked() {
            return verdict != Verdict.ALLOW;
        }
    }

    /**
     * @param rateWindowMillis       length of the rate window
     * @param duplicateExpireMillis  how long an earlier message still counts (0 = until
     *                               it leaves the history)
     * @param duplicateHistory       how many recent messages are remembered
     * @param duplicateSimilarity    0..1, how alike two messages must be to count as the same
     * @param muteMillis             mute per consecutive offence; empty = no mutes
     * @param penaltyResetMillis     offences are forgotten after this long without a new one
     */
    public record Settings(boolean rateEnabled, int rateMax, long rateWindowMillis,
                           boolean duplicateEnabled, int duplicateMaxSame, long duplicateExpireMillis,
                           int duplicateHistory, double duplicateSimilarity,
                           long[] muteMillis, long penaltyResetMillis) {
        /** The same settings with either limit switched off (bypass permissions). */
        public Settings limitedTo(boolean rate, boolean duplicate) {
            return new Settings(rateEnabled && rate, rateMax, rateWindowMillis,
                    duplicateEnabled && duplicate, duplicateMaxSame, duplicateExpireMillis,
                    duplicateHistory, duplicateSimilarity, muteMillis, penaltyResetMillis);
        }
    }

    private record Sent(String text, long at) {
    }

    private static final class State {
        long windowStart;
        int count;
        final ArrayDeque<Sent> recent = new ArrayDeque<>();
        long mutedUntil;
        int strikes;
        long lastStrikeAt;
        long lastSeen;
    }

    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();
    private volatile long lastPurge = System.currentTimeMillis();

    /**
     * Records one chat message or watched command and says whether it may go through.
     *
     * @param folded the text in comparison form (see {@link WordMatcher#fold})
     */
    public Result check(UUID playerId, String folded, Settings settings) {
        long now = System.currentTimeMillis();
        purgeIfDue(now);
        State state = states.computeIfAbsent(playerId, ignored -> new State());
        // Chat arrives on async threads, commands on the main thread.
        synchronized (state) {
            state.lastSeen = now;
            if (now < state.mutedUntil) {
                return new Result(Verdict.MUTED, secondsLeft(state.mutedUntil, now));
            }

            Verdict verdict = Verdict.ALLOW;
            if (settings.rateEnabled() && settings.rateMax() > 0) {
                if (state.count == 0 || now - state.windowStart > settings.rateWindowMillis()) {
                    state.windowStart = now;
                    state.count = 1;
                } else {
                    state.count++;
                }
                if (state.count >= settings.rateMax()) {
                    verdict = Verdict.RATE;
                }
            }
            if (verdict == Verdict.ALLOW && settings.duplicateEnabled() && settings.duplicateMaxSame() > 0
                    && folded != null && !folded.isEmpty()) {
                if (countSimilar(state, folded, now, settings) >= settings.duplicateMaxSame()) {
                    verdict = Verdict.DUPLICATE;
                } else {
                    // Only messages that went through are remembered, so a blocked
                    // repeat stays blocked instead of pushing the originals out.
                    state.recent.addLast(new Sent(folded, now));
                    while (state.recent.size() > Math.max(1, settings.duplicateHistory())) {
                        state.recent.pollFirst();
                    }
                }
            }
            if (verdict == Verdict.ALLOW) {
                return new Result(Verdict.ALLOW, 0);
            }

            long[] mutes = settings.muteMillis();
            if (mutes.length == 0) {
                return new Result(verdict, 0);
            }
            if (now - state.lastStrikeAt > settings.penaltyResetMillis()) {
                state.strikes = 0;
            }
            state.strikes++;
            state.lastStrikeAt = now;
            long mute = mutes[Math.min(state.strikes, mutes.length) - 1];
            state.mutedUntil = now + mute;
            // The mute is the penalty; start the rate count afresh afterwards, or the
            // first message after a short mute would land in the old window and be
            // punished again.
            state.count = 0;
            return new Result(verdict, mute <= 0 ? 0 : secondsLeft(state.mutedUntil, now));
        }
    }

    private static int countSimilar(State state, String folded, long now, Settings settings) {
        int similar = 0;
        for (Iterator<Sent> it = state.recent.iterator(); it.hasNext(); ) {
            Sent sent = it.next();
            if (settings.duplicateExpireMillis() > 0 && now - sent.at() > settings.duplicateExpireMillis()) {
                it.remove();
                continue;
            }
            if (similarity(sent.text(), folded) >= settings.duplicateSimilarity()) {
                similar++;
            }
        }
        return similar;
    }

    /** 1.0 for identical texts down to 0.0 for nothing in common (edit distance over length). */
    static double similarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        if (a.length() > MAX_COMPARE_LENGTH) {
            a = a.substring(0, MAX_COMPARE_LENGTH);
        }
        if (b.length() > MAX_COMPARE_LENGTH) {
            b = b.substring(0, MAX_COMPARE_LENGTH);
        }
        int longest = Math.max(a.length(), b.length());
        if (longest == 0) {
            return 1.0;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitute = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitute, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return 1.0 - (double) previous[b.length()] / longest;
    }

    private static long secondsLeft(long until, long now) {
        return Math.max(1L, (until - now + 999L) / 1000L);
    }

    private void purgeIfDue(long now) {
        if (now - lastPurge < PURGE_INTERVAL_MILLIS) {
            return;
        }
        lastPurge = now;
        states.values().removeIf(state -> {
            synchronized (state) {
                return now >= state.mutedUntil && now - state.lastSeen > IDLE_RETENTION_MILLIS;
            }
        });
    }
}
