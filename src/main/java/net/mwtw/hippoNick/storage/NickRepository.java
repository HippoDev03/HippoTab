package net.mwtw.hippoNick.storage;

import net.mwtw.hippoNick.domain.NickProfile;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface NickRepository extends AutoCloseable {
    Optional<NickProfile> find(UUID uuid);

    /**
     * Like {@link #find(UUID)} but never blocks on the storage backend: answers from the
     * in-memory cache only. On a miss a caching repository starts loading the profile in
     * the background and returns empty for now, so a later call gets the real answer.
     * For callers that run on the main thread with players who may be offline (placeholders).
     */
    default Optional<NickProfile> findCached(UUID uuid) {
        return find(uuid);
    }

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
