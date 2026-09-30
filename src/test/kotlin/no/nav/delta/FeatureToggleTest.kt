package no.nav.delta

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FeatureToggleTest {
    private val maintainers = "maintainers-group"

    @Test
    fun `off disables the feature, even for maintainers`() {
        val env = Environment(maintainersGroupId = maintainers, featureRoomBooking = FeatureAccess.OFF)
        assertFalse(env.isRoomBookingEnabledFor(listOf(maintainers)))
    }

    @Test
    fun `maintainers enables the feature only for members of the maintainers group`() {
        val env = Environment(maintainersGroupId = maintainers, featureRoomBooking = FeatureAccess.MAINTAINERS)
        assertTrue(env.isRoomBookingEnabledFor(listOf("other", maintainers)))
        assertFalse(env.isRoomBookingEnabledFor(listOf("other")))
        assertFalse(env.isRoomBookingEnabledFor(emptyList()))
    }

    @Test
    fun `maintainers enables nobody when the maintainers group id is not configured`() {
        val env = Environment(maintainersGroupId = "", featureRoomBooking = FeatureAccess.MAINTAINERS)
        assertFalse(env.isRoomBookingEnabledFor(listOf("")))
    }

    @Test
    fun `all enables the feature for everyone`() {
        val env = Environment(maintainersGroupId = maintainers, featureRoomBooking = FeatureAccess.ALL)
        assertTrue(env.isRoomBookingEnabledFor(emptyList()))
    }

    @Test
    fun `teams meeting toggle is independent of room booking`() {
        val env =
            Environment(
                maintainersGroupId = maintainers,
                featureRoomBooking = FeatureAccess.ALL,
                featureTeamsMeeting = FeatureAccess.MAINTAINERS,
            )
        assertTrue(env.isTeamsMeetingEnabledFor(listOf(maintainers)))
        assertFalse(env.isTeamsMeetingEnabledFor(emptyList()))
    }

    @Test
    fun `toggle values are parsed case-insensitively with true and false as aliases`() {
        assertEquals(FeatureAccess.OFF, FeatureAccess.parse(""))
        assertEquals(FeatureAccess.OFF, FeatureAccess.parse("false"))
        assertEquals(FeatureAccess.OFF, FeatureAccess.parse(" Off "))
        assertEquals(FeatureAccess.MAINTAINERS, FeatureAccess.parse("MAINTAINERS"))
        assertEquals(FeatureAccess.ALL, FeatureAccess.parse("all"))
        assertEquals(FeatureAccess.ALL, FeatureAccess.parse("true"))
        assertThrows<IllegalArgumentException> { FeatureAccess.parse("yes") }
    }
}
