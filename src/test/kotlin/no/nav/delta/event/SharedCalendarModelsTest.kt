package no.nav.delta.event

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SharedCalendarModelsTest {
    @Test
    fun `invitation requests accept only individual email and reject group ids`() {
        val mapper = jacksonObjectMapper()
        assertEquals(InviteeRequest("person@nav.no"), mapper.readValue(
            """{"email":"person@nav.no"}""", InviteeRequest::class.java,
        ))
        assertThrows(UnrecognizedPropertyException::class.java) {
            mapper.readValue("""{"email":"person@nav.no","groupId":"group"}""", InviteeRequest::class.java)
        }
    }
}
