package no.nav.delta.directory

import io.ktor.http.HttpStatusCode
import no.nav.delta.application.respondProblem
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
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
            if (call.principal<JWTPrincipal>()?.payload?.getClaim("idtyp")?.asString() == "app") {
                return@get call.respondProblem(HttpStatusCode.Forbidden, "Personsøk er kun tilgjengelig for brukere.")
            }
            if (!env.isSharedCalendarEnabledFor(call.principalGroups())) {
                return@get call.respondProblem(HttpStatusCode.BadRequest, "Personsøk er ikke slått på.")
            }
            val query = call.request.queryParameters["q"]?.trim().orEmpty()
            if (query.length !in 2..100 || query.any { it.isISOControl() }) {
                return@get call.respondProblem(HttpStatusCode.BadRequest, "Søket må inneholde 2 til 100 tegn.")
            }
            cloudClient.searchPeople(query).fold(
                ifLeft = {
                    if (it is SharedGraphException) {
                        logger.warn("Directory search failed with status {}", it.httpStatus)
                    } else {
                        logger.warn("Directory search failed ({})", it::class.simpleName)
                    }
                    call.respondProblem(HttpStatusCode.BadGateway, "Personsøk er utilgjengelig akkurat nå.")
                },
                ifRight = { call.respond(it) },
            )
        }
    }
}
