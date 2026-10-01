package no.nav.delta.room

import arrow.core.Either
import arrow.core.right
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import no.nav.delta.email.CloudClient
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("no.nav.delta.room.RoomCache")

// Rooms and buildings change rarely (weeks), so serve cached data for a day and refresh it in
// the background after that.
private val ROOM_CACHE_TTL: Duration = Duration.ofHours(24)
private val RETRY_AFTER_FAILED_REFRESH: Duration = Duration.ofMinutes(5)
private const val MAX_ROOM_LIST_CACHE_ENTRIES = 100

private val refreshExecutor: Executor =
    Executors.newSingleThreadExecutor { Thread(it, "room-cache-refresh").apply { isDaemon = true } }

/**
 * Stale-while-revalidate cache. The first load is synchronous and only successful results are
 * cached, so a failed first load is retried on the next call. Once data is older than [ttl] it
 * is still returned immediately while a single background refresh runs; if that refresh fails
 * the stale data is kept and the refresh is retried after [retryAfterFailure].
 */
internal class StaleWhileRevalidateCache<T>(
    private val name: String,
    private val ttl: Duration = ROOM_CACHE_TTL,
    private val retryAfterFailure: Duration = RETRY_AFTER_FAILED_REFRESH,
    private val clock: Clock = Clock.systemUTC(),
    private val executor: Executor = refreshExecutor,
) {
    private class Entry<T>(val value: T, val fetchedAt: Instant)

    @Volatile private var entry: Entry<T>? = null
    @Volatile private var lastFailedRefreshAt: Instant? = null
    private val refreshing = AtomicBoolean(false)
    private val loadLock = Any()

    fun getOrLoad(load: () -> Either<Throwable, T>): Either<Throwable, T> {
        entry?.let { current ->
            if (shouldRefresh(current)) refreshInBackground(load)
            return current.value.right()
        }
        synchronized(loadLock) {
            entry?.let { return it.value.right() }
            return load().onRight { entry = Entry(it, clock.instant()) }
        }
    }

    private fun shouldRefresh(current: Entry<T>): Boolean {
        val now = clock.instant()
        if (Duration.between(current.fetchedAt, now) < ttl) return false
        val failedAt = lastFailedRefreshAt ?: return true
        return Duration.between(failedAt, now) >= retryAfterFailure
    }

    private fun refreshInBackground(load: () -> Either<Throwable, T>) {
        if (!refreshing.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    load().fold(
                        { error ->
                            lastFailedRefreshAt = clock.instant()
                            logger.warn("Background refresh of $name failed; serving stale data", error)
                        },
                        {
                            entry = Entry(it, clock.instant())
                            lastFailedRefreshAt = null
                        },
                    )
                } catch (e: Exception) {
                    lastFailedRefreshAt = clock.instant()
                    logger.warn("Background refresh of $name failed; serving stale data", e)
                } finally {
                    refreshing.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            refreshing.set(false)
            logger.warn("Could not schedule background refresh of $name", e)
        }
    }
}

/** An access-ordered cache map that evicts the least recently used entry at capacity. */
private class BoundedCacheMap<K, V>(private val maxEntries: Int) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)

    @Synchronized
    fun getOrCreate(key: K, create: () -> V): V =
        entries[key] ?: create().also {
            entries[key] = it
            if (entries.size > maxEntries) {
                entries.entries.iterator().run {
                    next()
                    remove()
                }
            }
        }
}

/** Cached view of the tenant's rooms and room lists from Graph. */
class RoomCatalog(private val cloudClient: CloudClient) {
    private val roomLists = StaleWhileRevalidateCache<List<RoomList>>("room lists")
    private val allRooms = StaleWhileRevalidateCache<List<RoomInfo>>("all rooms")
    private val roomsByList = BoundedCacheMap<String, StaleWhileRevalidateCache<List<RoomInfo>>>(MAX_ROOM_LIST_CACHE_ENTRIES)

    fun roomLists(): Either<Throwable, List<RoomList>> = roomLists.getOrLoad { cloudClient.getRoomLists() }

    fun allRooms(): Either<Throwable, List<RoomInfo>> = allRooms.getOrLoad { cloudClient.getAllRooms() }

    fun rooms(roomListEmail: String): Either<Throwable, List<RoomInfo>> =
        roomsByList
            .getOrCreate(roomListEmail) { StaleWhileRevalidateCache("rooms in $roomListEmail") }
            .getOrLoad { cloudClient.getRooms(roomListEmail) }

    /** Loads search data up front so the first user doesn't wait for the full Graph fetch. */
    fun warmUp() {
        allRooms().onLeft { logger.warn("Failed to warm up room cache", it) }
        roomLists().onLeft { logger.warn("Failed to warm up room list cache", it) }
    }
}
