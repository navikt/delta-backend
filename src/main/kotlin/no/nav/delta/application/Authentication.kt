package no.nav.delta.application

//import net.logstash.logback.argument.StructuredArguments
import com.auth0.jwk.JwkProvider
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
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
                    return@skipWhen true
                }
                false
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
