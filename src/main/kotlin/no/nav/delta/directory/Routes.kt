package no.nav.delta.directory

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import no.nav.delta.Environment
import no.nav.delta.email.CloudClient
import no.nav.delta.email.SharedGraphException
import no.nav.delta.event.principalGroups
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("no.nav.delta.directory")

fun Route.directoryApi(cloudClient: CloudClient, env: Environment) {
    authenticate("jwt") {
        get("/directory/search") {
            if (!env.isSharedCalendarEnabledFor(call.principalGroups())) {
                return@get call.respond(HttpStatusCode.BadRequest, "People search is not enabled")
            }
            val query = call.request.queryParameters["q"]?.trim().orEmpty()
            if (query.length !in 2..100 || query.any { it.isISOControl() }) {
                return@get call.respond(HttpStatusCode.BadRequest, "Search query must contain 2 to 100 characters")
            }
            cloudClient.searchPeople(query).fold(
                ifLeft = {
                    if (it is SharedGraphException) {
                        logger.warn("Directory search failed with status {}", it.httpStatus)
                    } else {
                        logger.warn("Directory search failed ({})", it::class.simpleName)
                    }
                    call.respond(HttpStatusCode.BadGateway, "People search is unavailable")
                },
                ifRight = { call.respond(it) },
            )
        }
    }
}
