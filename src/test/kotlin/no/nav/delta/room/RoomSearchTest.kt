package no.nav.delta.room

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RoomSearchTest {
    private fun room(name: String, email: String) = RoomInfo(name, email, null, null, null, null)

    private val rooms =
        listOf(
            room("(RV) FYA1 - A347 Kaptein - Videokonf", "fya1-a347@nav.no"),
            room("(RV) FYA1 - AU01 Auditorium", "fya1-au01@nav.no"),
            room("(RV) FYA2 - B210 Styrmann", "fya2-b210@nav.no"),
        )

    private fun names(q: String, limit: Int = 25) = searchRooms(rooms, q, limit).map { it.emailAddress }

    @Test
    fun `terms match in any order, case-insensitively, ignoring punctuation`() {
        assertEquals(listOf("fya1-a347@nav.no"), names("kaptein fya1"))
        assertEquals(listOf("fya1-a347@nav.no"), names("A347"))
        assertEquals(listOf("fya1-a347@nav.no", "fya1-au01@nav.no"), names("fya1"))
        assertEquals(listOf("fya1-au01@nav.no"), names("(rv) au01"))
    }

    @Test
    fun `all terms must match and email is searchable`() {
        assertEquals(emptyList<String>(), names("fya2 kaptein"))
        assertEquals(listOf("fya2-b210@nav.no"), names("fya2-b210@"))
    }

    @Test
    fun `rooms with a word starting with the first term are ranked first, and limit applies`() {
        val ranked = listOf(room("(RV) Alpha - Styrmann", "x@nav.no"), room("(RV) Zulu - Mannskap", "y@nav.no"))
        assertEquals(listOf("y@nav.no", "x@nav.no"), searchRooms(ranked, "mann", 25).map { it.emailAddress })
        assertEquals(1, searchRooms(rooms, "rv", 1).size)
    }
}
