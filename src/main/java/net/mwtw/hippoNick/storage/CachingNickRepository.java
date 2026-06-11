package net.mwtw.hippoNick.storage;

import net.mwtw.hippoNick.domain.NickProfile;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory write-through cache over any {@link NickRepository}.
 *
 * <p>{@link NickManager} (and the PacketEvents tab listener in particular) calls
 * {@link #find(UUID)} from the main thread and from Netty packet threads, often
 * once or twice per outbound tab packet. Without a cache the {@code mariadb}
 * backend would perform a blocking JDBC round-trip on every one of those calls,
 * which lags the server and exhausts the connection pool. This decorator makes
 * reads O(1) after the first load and only hits the delegate on cache misses or
 * writes.</p>
 *
 * <p>The cache stores {@link Optional} so that "known absent" is cached too,
 * preventing repeated misses for un-nicked players.</p>
 */
public final class CachingNickRepository implements NickRepository {
    private final NickRepository delegate;
    private final ConcurrentHashMap<UUID, Optional<NickProfile>> cache = new ConcurrentHashMap<>();

    public CachingNickRepository(NickRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<NickProfile> find(UUID uuid) {
        return cache.computeIfAbsent(uuid, delegate::find);
    }

    @Override
    public Collection<NickProfile> findAll() {
        Collection<NickProfile> all = delegate.findAll();
        for (NickProfile profile : all) {
            cache.put(profile.uuid(), Optional.of(profile));
        }
        return all;
    }

    @Override
    public void save(NickProfile profile) {
        delegate.save(profile);
        cache.put(profile.uuid(), Optional.of(profile));
    }

    @Override
    public void delete(UUID uuid) {
        delegate.delete(uuid);
        cache.put(uuid, Optional.empty());
    }

    @Override
    public void preload(UUID uuid) {
        cache.computeIfAbsent(uuid, delegate::find);
    }

    @Override
    public void invalidate(UUID uuid) {
        cache.remove(uuid);
    }

    @Override
    public void close() {
        delegate.close();
        cache.clear();
    }
}
