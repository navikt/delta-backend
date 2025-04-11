package no.nav.delta.application

import com.auth0.jwk.JwkProvider
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import no.nav.delta.Environment

fun Application.setupAuth(
    jwkProvider: JwkProvider,
    env: Environment
) {
    install(Authentication) {
        jwt(name = "jwt") {
            skipWhen { env.isLocal }
            verifier(jwkProvider, env.jwtIssuer)
            validate { credentials ->
                JWTPrincipal(credentials.payload)
            }
        }
    }
}