package net.mwtw.hippoNick.storage;

import net.mwtw.hippoNick.domain.NickProfile;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory write-behind cache over any {@link NickRepository}.
 *
 * <p>{@link NickManager} (and the PacketEvents tab listener in particular) calls
 * {@link #find(UUID)} from the main thread and from Netty packet threads, often
 * once or twice per outbound tab packet. Without a cache the {@code mariadb}
 * backend would perform a blocking JDBC round-trip on every one of those calls,
 * which lags the server and exhausts the connection pool. This decorator makes
 * reads O(1) after the first load and only hits the delegate on cache misses.</p>
 *
 * <p>Writes update the cache immediately and are persisted in order on a single
 * background thread, so a command or GUI click never waits on the backend.</p>
 *
 * <p>The cache stores {@link Optional} so that "known absent" is cached too,
 * preventing repeated misses for un-nicked players. A failed load is NOT cached as
 * absent — it is only held off for a short while so an outage cannot hammer the
 * backend from packet threads.</p>
 */
public final class CachingNickRepository implements NickRepository {
    private static final long LOAD_RETRY_MILLIS = 30_000L;

    private final NickRepository delegate;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, Optional<NickProfile>> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> failedLoads = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HippoNick-storage");
        thread.setDaemon(true);
        return thread;
    });

    public CachingNickRepository(NickRepository delegate, Logger logger) {
        this.delegate = delegate;
        this.logger = logger;
    }

    /** Profiles currently being loaded in the background for {@link #findCached(UUID)}. */
    private final java.util.Set<UUID> backgroundLoads = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The storage backend behind this cache. */
    public NickRepository delegate() {
        return delegate;
    }

    @Override
    public Optional<NickProfile> find(UUID uuid) {
        Optional<NickProfile> cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }
        Long failedAt = failedLoads.get(uuid);
        if (failedAt != null && System.currentTimeMillis() - failedAt < LOAD_RETRY_MILLIS) {
            return Optional.empty();
        }
        try {
            Optional<NickProfile> loaded = cache.computeIfAbsent(uuid, delegate::find);
            failedLoads.remove(uuid);
            return loaded;
        } catch (RuntimeException exception) {
            failedLoads.put(uuid, System.currentTimeMillis());
            logger.log(Level.SEVERE, "Failed to load nick profile for " + uuid, exception);
            return Optional.empty();
        }
    }

    @Override
    public Optional<NickProfile> findCached(UUID uuid) {
        Optional<NickProfile> cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }
        // Not known yet: load it off-thread (once), answer "no nick" for now.
        if (backgroundLoads.add(uuid)) {
            write(() -> {
                try {
                    find(uuid);
                } finally {
                    backgroundLoads.remove(uuid);
                }
            }, "load nick profile for " + uuid);
        }
        return Optional.empty();
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
        cache.put(profile.uuid(), Optional.of(profile));
        write(() -> delegate.save(profile), "save nick profile for " + profile.uuid());
    }

    @Override
    public void delete(UUID uuid) {
        cache.put(uuid, Optional.empty());
        write(() -> delegate.delete(uuid), "delete nick profile for " + uuid);
    }

    @Override
    public void preload(UUID uuid) {
        find(uuid);
    }

    @Override
    public void invalidate(UUID uuid) {
        failedLoads.remove(uuid);
        // Queued behind any pending write for this player, so a reload after the entry is
        // dropped can never read the backend before that write has landed.
        write(() -> cache.remove(uuid), "invalidate nick profile for " + uuid);
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Timed out waiting for pending nick profile writes.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        delegate.close();
        cache.clear();
    }

    private void write(Runnable task, String description) {
        Runnable guarded = () -> {
            try {
                task.run();
            } catch (RuntimeException exception) {
                logger.log(Level.SEVERE, "Failed to " + description, exception);
            }
        };
        if (writer.isShutdown()) {
            guarded.run();
            return;
        }
        writer.execute(guarded);
    }
}
