package no.nav.delta.email

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FetchAllPlacesTest {
    private val all = (1..2500).map { "room-$it@nav.no" }

    @Test
    fun `fetches beyond the first page using skip`() {
        val skips = mutableListOf<Int>()
        val result = fetchAllPlaces<String>({ it }) { top, skip ->
            skips += skip
            all.drop(skip).take(top)
        }
        assertEquals(all, result)
        assertEquals(listOf(0, 999, 1998, 2500), skips)
    }

    @Test
    fun `stops when skip is ignored by the server`() {
        var calls = 0
        val result = fetchAllPlaces<String>({ it }) { top, _ ->
            calls++
            all.take(top)
        }
        assertEquals(999, result.size)
        assertEquals(2, calls)
    }

    @Test
    fun `handles server page size smaller than requested top`() {
        val result = fetchAllPlaces<String>({ it }) { _, skip -> all.drop(skip).take(100) }
        assertEquals(all, result)
    }
}
