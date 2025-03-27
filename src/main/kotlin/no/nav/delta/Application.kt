package no.nav.delta

import com.auth0.jwk.JwkProviderBuilder
import java.util.concurrent.TimeUnit
import no.nav.delta.application.createApplicationEngine
import no.nav.delta.email.CloudClient
import no.nav.delta.plugins.DatabaseConfig
import java.net.URI

fun main() {
    val environment = Environment()
    val databaseConfig = DatabaseConfig(environment)
    val cloudClient = CloudClient.fromEnvironment(environment)

    val jwkProvider =
        JwkProviderBuilder(URI(environment.jwkKeysUrl).toURL())
            .cached(10, 24, TimeUnit.HOURS)
            .rateLimited(10, 1, TimeUnit.MINUTES)
            .build()

    createApplicationEngine(environment, databaseConfig, cloudClient, jwkProvider).start(wait = true)
}
