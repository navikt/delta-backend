package no.nav.delta

import com.auth0.jwk.JwkProviderBuilder
import java.net.URL
import java.util.concurrent.TimeUnit
import no.nav.delta.application.createApplicationEngine
import no.nav.delta.plugins.Database
import no.nav.delta.email.CloudClient

fun main() {
    val environment = Environment()
    val database = Database(environment)
    val cloudClient = CloudClient.fromEnvironment(environment)

    val jwkProvider =
        JwkProviderBuilder(URL(environment.jwkKeysUrl))
            .cached(10, 24, TimeUnit.HOURS)
            .rateLimited(10, 1, TimeUnit.MINUTES)
            .build()

    createApplicationEngine(environment, database, cloudClient, jwkProvider).start(wait = true)
}
