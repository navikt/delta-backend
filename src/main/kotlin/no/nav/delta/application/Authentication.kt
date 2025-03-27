package no.nav.delta.application

import com.auth0.jwk.JwkProvider
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt

fun Application.setupAuth(
    jwkProvider: JwkProvider,
    issuer: String,
) {
    install(Authentication) {
        jwt(name = "jwt") {
            verifier(jwkProvider, issuer)
            validate { credentials ->
                credentials.payload
            }
        }
    }
}