package net.mwtw.hippoNick.service;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.mwtw.hippoNick.Core;
import net.mwtw.hippoNick.domain.RankInfo;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class LuckPermsRankService implements RankService {
    private final Core plugin;
    private final Map<String, RankInfo> rankCache = new ConcurrentHashMap<>();
    private volatile long lastRefreshMillis = 0L;

    public LuckPermsRankService(Core plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<RankInfo> listRanks() {
        refreshIfNeeded();
        ArrayList<RankInfo> out = new ArrayList<>(rankCache.values());
        out.sort(Comparator.comparingInt(RankInfo::weight).reversed().thenComparing(RankInfo::name));
        return out;
    }

    @Override
    public Optional<String> findCanonicalRank(String rankInput) {
        if (rankInput == null || rankInput.isBlank()) {
            return Optional.empty();
        }
        refreshIfNeeded();
        RankInfo rank = rankCache.get(rankInput.toLowerCase(Locale.ROOT));
        return rank == null ? Optional.empty() : Optional.of(rank.name());
    }

    @Override
    public String resolveBaseRank(Player player) {
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                user = luckPerms.getUserManager().loadUser(player.getUniqueId()).join();
            }
            if (user == null) {
                return "default";
            }
            return user.getPrimaryGroup();
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Unable to resolve base rank for " + player.getName(), exception);
            return "default";
        }
    }

    @Override
    public Optional<String> resolvePrefix(String rankName) {
        if (rankName == null || rankName.isBlank()) {
            return Optional.empty();
        }
        refreshIfNeeded();
        RankInfo rank = rankCache.get(rankName.toLowerCase(Locale.ROOT));
        if (rank == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(rank.prefix()).filter(value -> !value.isBlank());
    }

    @Override
    public Optional<Integer> resolveWeight(String rankName) {
        if (rankName == null || rankName.isBlank()) {
            return Optional.empty();
        }
        refreshIfNeeded();
        RankInfo rank = rankCache.get(rankName.toLowerCase(Locale.ROOT));
        return rank == null ? Optional.empty() : Optional.of(rank.weight());
    }

    private void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMillis < TimeUnit.SECONDS.toMillis(30)) {
            return;
        }
        synchronized (this) {
            if (now - lastRefreshMillis < TimeUnit.SECONDS.toMillis(30)) {
                return;
            }
            refreshNow();
            lastRefreshMillis = now;
        }
    }

    private void refreshNow() {
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            Map<String, Group> groups = luckPerms.getGroupManager().getLoadedGroups().stream()
                    .collect(java.util.stream.Collectors.toMap(Group::getName, group -> group));
            if (groups.isEmpty()) {
                luckPerms.getGroupManager().loadAllGroups().join();
                groups = luckPerms.getGroupManager().getLoadedGroups().stream()
                        .collect(java.util.stream.Collectors.toMap(Group::getName, group -> group));
            }

            rankCache.clear();
            for (Group group : groups.values()) {
                int weight = group.getWeight().orElse(0);
                String prefix = group.getCachedData().getMetaData().getPrefix();
                RankInfo rank = new RankInfo(group.getName(), weight, prefix);
                rankCache.put(group.getName().toLowerCase(Locale.ROOT), rank);
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Unable to refresh LuckPerms ranks", exception);
        }
    }
}
