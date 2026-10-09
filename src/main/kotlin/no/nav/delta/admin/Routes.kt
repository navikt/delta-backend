package no.nav.delta.admin

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import no.nav.delta.Environment
import no.nav.delta.application.enforceM2mReadOnlyAccess
import no.nav.delta.event.LOCAL_PRINCIPAL_GROUP
import no.nav.delta.plugins.DatabaseInterface

fun Route.adminApi(database: DatabaseInterface, env: Environment) {
    authenticate("jwt") {
        enforceM2mReadOnlyAccess()
        get("/admin/statistics") {
            call.response.headers.append("Cache-Control", "no-store")
            val principal = call.principal<JWTPrincipal>()
            val groups = principal?.payload?.getClaim("groups")?.asList(String::class.java)
                ?: if (env.isLocal && principal == null) listOf(LOCAL_PRINCIPAL_GROUP) else emptyList()
            if (principal?.payload?.getClaim("idtyp")?.asString() == "app" ||
                env.maintainersGroupId.isBlank() || env.maintainersGroupId !in groups
            ) {
                call.respond(HttpStatusCode.Forbidden, "Delta maintainer access required")
                return@get
            }
            call.respond(database.getAdminStatistics())
        }
    }
}
