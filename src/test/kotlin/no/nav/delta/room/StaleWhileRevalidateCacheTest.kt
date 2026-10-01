package no.nav.delta.room

import arrow.core.left
import arrow.core.right
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StaleWhileRevalidateCacheTest {
    private class MutableClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    private val clock = MutableClock()
    private val queued = mutableListOf<Runnable>()
    private val queueingExecutor = Executor { queued += it }

    private fun cache(executor: Executor = queueingExecutor) =
        StaleWhileRevalidateCache<String>(
            name = "test",
            ttl = Duration.ofHours(24),
            retryAfterFailure = Duration.ofMinutes(5),
            clock = clock,
            executor = executor,
        )

    @Test
    fun `fresh value is served from cache`() {
        val cache = cache()
        var calls = 0
        cache.getOrLoad { calls++; "v1".right() }
        clock.now = clock.now.plus(Duration.ofHours(23))

        assertEquals("v1".right(), cache.getOrLoad { calls++; "v2".right() })
        assertEquals(1, calls)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `stale value is served immediately while a single refresh runs in the background`() {
        val cache = cache()
        cache.getOrLoad { "v1".right() }
        clock.now = clock.now.plus(Duration.ofHours(25))

        assertEquals("v1".right(), cache.getOrLoad { "v2".right() })
        assertEquals("v1".right(), cache.getOrLoad { "v3".right() })
        assertEquals(1, queued.size)

        queued.removeAt(0).run()

        assertEquals("v2".right(), cache.getOrLoad { "unused".right() })
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `failed refresh keeps stale value and retries after backoff`() {
        val cache = cache(Executor { it.run() })
        cache.getOrLoad { "v1".right() }
        clock.now = clock.now.plus(Duration.ofHours(25))
        var calls = 0

        assertEquals("v1".right(), cache.getOrLoad { calls++; RuntimeException("graph down").left() })
        assertEquals("v1".right(), cache.getOrLoad { calls++; RuntimeException("graph down").left() })
        assertEquals(1, calls)

        clock.now = clock.now.plus(Duration.ofMinutes(5))
        cache.getOrLoad { calls++; "v2".right() }

        assertEquals(2, calls)
        assertEquals("v2".right(), cache.getOrLoad { "unused".right() })
    }

    @Test
    fun `failed first load is not cached`() {
        val cache = cache()

        assertTrue(cache.getOrLoad { RuntimeException("graph down").left() }.isLeft())
        assertEquals("v1".right(), cache.getOrLoad { "v1".right() })
    }
}
