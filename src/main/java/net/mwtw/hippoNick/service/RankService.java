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
}
