package no.nav.delta.feature

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import no.nav.delta.Environment
import no.nav.delta.event.principalGroups

data class Features(
    val roomBooking: Boolean,
    val teamsMeeting: Boolean,
    val sharedCalendar: Boolean = false,
    val peopleSearch: Boolean = false,
)

fun Route.featureApi(env: Environment) {
    authenticate("jwt") {
        route("/features") {
            get {
                call.respond(
                    Features(
                        roomBooking = env.isRoomBookingEnabledFor(call.principalGroups()),
                        teamsMeeting = env.isTeamsMeetingEnabledFor(call.principalGroups()),
                        sharedCalendar = env.isSharedCalendarEnabledFor(call.principalGroups()),
                        peopleSearch = env.isSharedCalendarEnabledFor(call.principalGroups()),
                    )
                )
            }
        }
    }
}
