package net.mwtw.hippoNick.domain;

import java.time.Instant;
import java.util.UUID;

public record NickProfile(
        UUID uuid,
        String nickname,
        String rankOverride,
        String skinName,
        Instant updatedAt
) {
    public NickProfile withNickname(String value) {
        return new NickProfile(uuid, emptyToNull(value), rankOverride, skinName, Instant.now());
    }

    public NickProfile withRankOverride(String value) {
        return new NickProfile(uuid, nickname, emptyToNull(value), skinName, Instant.now());
    }

    public NickProfile withSkinName(String value) {
        return new NickProfile(uuid, nickname, rankOverride, emptyToNull(value), Instant.now());
    }

    public NickProfile clearAll() {
        return new NickProfile(uuid, null, null, null, Instant.now());
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
