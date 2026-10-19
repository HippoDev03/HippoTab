package net.mwtw.hippoNick.service;

import net.mwtw.hippoNick.domain.RankInfo;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

public interface RankService {
    List<RankInfo> listRanks();

    Optional<String> findCanonicalRank(String rankInput);

    String resolveBaseRank(Player player);

    Optional<String> resolvePrefix(String rankName);

    Optional<Integer> resolveWeight(String rankName);

    /** A meta value of the rank's group, e.g. a short icon set with {@code meta set icon ...}. */
    Optional<String> resolveMeta(String rankName, String key);
}
