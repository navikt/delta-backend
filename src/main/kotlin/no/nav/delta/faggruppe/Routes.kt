package no.nav.delta.faggruppe

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID
import no.nav.delta.Environment
import no.nav.delta.email.CloudClient
import no.nav.delta.plugins.DatabaseInterface

fun Route.faggruppeApi(database: DatabaseInterface, cloudClient: CloudClient, env: Environment) {
    authenticate("jwt") {
        route("/api/faggrupper") {
            get {
                call.respond(database.getAllActiveFaggrupper())
            }

            post {
                val dto = call.receive<FaggruppeCreateDTO>()
                val email = call.principalEmail()
                val navn = call.principalName()

                val created = database.createFaggruppe(dto, email, navn)
                call.respond(HttpStatusCode.Created, created)
            }

            route("/{id}") {
                get {
                    val id = call.faggruppeId() ?: return@get
                    val faggruppe = database.getFaggruppe(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respond(faggruppe)
                }

                put {
                    val id = call.faggruppeId() ?: return@put
                    if (!database.fagruppeExists(id)) return@put call.respond(HttpStatusCode.NotFound)
                    if (!call.isOwnerOrAdmin(database, id, env)) return@put call.respond(HttpStatusCode.Forbidden)

                    val dto = call.receive<FaggruppeUpdateDTO>()
                    val updated = database.updateFaggruppe(id, dto)
                        ?: return@put call.respond(HttpStatusCode.NotFound)
                    call.respond(updated)
                }

                get("/eier") {
                    val id = call.faggruppeId() ?: return@get
                    val email = call.principalEmail()
                    call.respond(mapOf("isOwner" to database.isEier(id, email)))
                }

                route("/eiere") {
                    post {
                        val id = call.faggruppeId() ?: return@post
                        if (!database.fagruppeExists(id)) return@post call.respond(HttpStatusCode.NotFound)
                        if (!call.isOwnerOrAdmin(database, id, env)) return@post call.respond(HttpStatusCode.Forbidden)

                        val dto = call.receive<AddEierDTO>()
                        if (database.eierExists(id, dto.epost)) {
                            return@post call.respond(HttpStatusCode.Conflict)
                        }

                        val navn = cloudClient.getUserDisplayName(dto.epost)
                        val eier = database.addEier(id, dto.epost, navn)
                        call.respond(eier)
                    }

                    delete("/{epost}") {
                        val id = call.faggruppeId() ?: return@delete
                        if (!database.fagruppeExists(id)) return@delete call.respond(HttpStatusCode.NotFound)
                        if (!call.isOwnerOrAdmin(database, id, env)) return@delete call.respond(HttpStatusCode.Forbidden)

                        val epost = call.parameters["epost"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest)
                        if (!database.eierExists(id, epost)) return@delete call.respond(HttpStatusCode.NotFound)

                        database.removeEier(id, epost)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.faggruppeId(): UUID? {
    val raw = parameters["id"] ?: run { respond(HttpStatusCode.BadRequest); return null }
    return try {
        UUID.fromString(raw)
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, "Invalid UUID")
        null
    }
}

private suspend fun ApplicationCall.isOwnerOrAdmin(
    database: DatabaseInterface,
    faggruppeId: UUID,
    env: Environment,
): Boolean {
    val email = principalEmail()
    val groups = principalGroups()
    return groups.contains(env.faggruppeAdminGroupId) || database.isEier(faggruppeId, email)
}

private fun ApplicationCall.principalEmail(): String {
    return if (System.getenv("NAIS_CLUSTER_NAME").isNullOrEmpty()) {
        "test@localhost"
    } else {
        principal<JWTPrincipal>()!!["preferred_username"]!!.lowercase()
    }
}

private fun ApplicationCall.principalName(): String {
    return if (System.getenv("NAIS_CLUSTER_NAME").isNullOrEmpty()) {
        "Test User"
    } else {
        principal<JWTPrincipal>()!!["name"]!!
    }
}

private fun ApplicationCall.principalGroups(): List<String> {
    return if (System.getenv("NAIS_CLUSTER_NAME").isNullOrEmpty()) {
        emptyList()
    } else {
        principal<JWTPrincipal>()!!.payload.getClaim("groups")
            ?.asList(String::class.java) ?: emptyList()
    }
}
