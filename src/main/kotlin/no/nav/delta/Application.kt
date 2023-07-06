package no.nav.delta

import com.auth0.jwk.JwkProviderBuilder
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import no.nav.delta.application.createApplicationEngine
import no.nav.delta.plugins.*
import java.net.URL
import java.util.concurrent.TimeUnit

fun main() {
    val environment = Environment()
    val database = Database(environment)

    val jwkProvider =
        JwkProviderBuilder(URL(environment.jwkKeysUrl))
            .cached(10, 24, TimeUnit.HOURS)
            .rateLimited(10, 1, TimeUnit.MINUTES)
            .build()

    createApplicationEngine(environment, database, jwkProvider).start(wait = true)
}


