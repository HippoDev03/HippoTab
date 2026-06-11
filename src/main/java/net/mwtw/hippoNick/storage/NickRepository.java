package net.mwtw.hippoNick.storage;

import net.mwtw.hippoNick.domain.NickProfile;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface NickRepository extends AutoCloseable {
    Optional<NickProfile> find(UUID uuid);

    Collection<NickProfile> findAll();

    void save(NickProfile profile);

    void delete(UUID uuid);

    /**
     * Loads the profile for {@code uuid} into any in-memory cache so that later
     * {@link #find(UUID)} calls (including those on packet/main threads) never
     * touch the underlying backend. No-op for non-caching repositories.
     */
    default void preload(UUID uuid) {
    }

    /**
     * Drops any cached state for {@code uuid} (e.g. on quit) to avoid leaks.
     * No-op for non-caching repositories.
     */
    default void invalidate(UUID uuid) {
    }

    @Override
    void close();
}
