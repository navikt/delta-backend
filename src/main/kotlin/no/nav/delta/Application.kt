package no.nav.delta

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwk.JwkProvider
import java.util.concurrent.TimeUnit
import no.nav.delta.application.createApplicationEngine
import no.nav.delta.email.CloudClient
import no.nav.delta.plugins.DatabaseConfig
import no.nav.delta.plugins.DatabaseInterface
import java.net.URI

data class ApplicationDependencies(
    val database: DatabaseInterface,
    val cloudClient: CloudClient,
    val jwkProvider: JwkProvider,
)

fun createJwkProvider(environment: Environment): JwkProvider =
    JwkProviderBuilder(URI(environment.jwkKeysUrl).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

fun createApplicationDependencies(
    environment: Environment = Environment(),
    databaseFactory: (Environment) -> DatabaseInterface = ::DatabaseConfig,
    cloudClientFactory: (Environment) -> CloudClient = CloudClient.Companion::fromEnvironment,
    jwkProviderFactory: (Environment) -> JwkProvider = ::createJwkProvider,
): ApplicationDependencies =
    ApplicationDependencies(
        database = databaseFactory(environment),
        cloudClient = cloudClientFactory(environment),
        jwkProvider = jwkProviderFactory(environment),
    )

fun main() {
    val environment = Environment()
    val dependencies = createApplicationDependencies(environment)

    createApplicationEngine(
        environment,
        dependencies.database,
        dependencies.cloudClient,
        dependencies.jwkProvider,
    ).start(wait = true)
}
