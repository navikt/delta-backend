package no.nav.delta.application

import com.auth0.jwk.JwkProvider
import com.auth0.jwt.interfaces.JWTVerifier
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.intercept
import no.nav.delta.Environment

const val DELTA_READ_ROLE = "delta.read"
private val eventByIdPath = Regex("/event/[^/]+")

fun Application.setupAuth(
    jwkProvider: JwkProvider,
    env: Environment,
    testVerifier: JWTVerifier? = null,
) {
    install(Authentication) {
        jwt(name = "jwt") {
            skipWhen { env.isLocal && testVerifier == null }
            if (testVerifier == null) {
                verifier(jwkProvider, env.jwtIssuer)
            } else {
                verifier(testVerifier)
            }
            validate { credentials ->
                JWTPrincipal(credentials.payload)
            }
        }
    }
}

fun Route.enforceM2mReadOnlyAccess() {
    intercept(ApplicationCallPipeline.Call) {
        val call = context
        val principal = call.principal<JWTPrincipal>() ?: return@intercept
        val payload = principal.payload
        val isApplicationToken = payload.getClaim("idtyp").asString() == "app"
        val hasReadRole =
            payload.getClaim("roles").asList(String::class.java)?.contains(DELTA_READ_ROLE) == true
        val path = call.request.path()
        val isReadApiPath =
            path == "/event" || path == "/category" || eventByIdPath.matches(path)
        val isApprovedRead =
            call.request.httpMethod == HttpMethod.Get && isReadApiPath

        if ((isApplicationToken && isReadApiPath && (!hasReadRole || !isApprovedRead)) ||
            (hasReadRole && (!isApplicationToken || !isApprovedRead))
        ) {
            val message =
                if (hasReadRole) {
                    "M2M access is restricted to approved read endpoints"
                } else {
                    "M2M access requires the delta.read role on approved read endpoints"
                }
            call.respond(HttpStatusCode.Forbidden, message)
            finish()
        }
    }
}