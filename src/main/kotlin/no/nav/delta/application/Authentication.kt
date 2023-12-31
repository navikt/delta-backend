package no.nav.delta.application

// import net.logstash.logback.argument.StructuredArguments
import com.auth0.jwk.JwkProvider
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.Principal
import io.ktor.server.auth.jwt.JWTCredential
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import no.nav.delta.Environment
import no.nav.delta.plugins.addDummyPrincipal

fun Application.setupAuth(
    environment: Environment,
    jwkProvider: JwkProvider,
    issuer: String,
) {
    install(Authentication) {
        jwt(name = "jwt") {
            skipWhen { call ->
                if (environment.development) {
                    // This predicate is modifying state -> I know this is a bit yuck...
                    addDummyPrincipal(call)
                    true
                } else false
            }
            verifier(jwkProvider, issuer)
            validate { credentials ->
                when {
                    hasDeltaBackendClientAudience(credentials, environment) ->
                        JWTPrincipal(credentials.payload)
                    else -> {
                        unauthorized(credentials)
                    }
                }
            }
        }
    }
}

fun unauthorized(credentials: JWTCredential): Principal? {
    /*log.warn(
        "Auth: Unexpected audience for jwt {}, {}",
        StructuredArguments.keyValue("issuer", credentials.payload.issuer),
        StructuredArguments.keyValue("audience", credentials.payload.audience),
    )*/
    return null
}

fun hasDeltaBackendClientAudience(credentials: JWTCredential, env: Environment): Boolean {
    return credentials.payload.audience.contains(env.azureAppClientId)
}
