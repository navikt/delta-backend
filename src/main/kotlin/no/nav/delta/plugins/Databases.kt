package no.nav.delta.plugins

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import no.nav.delta.Environment
import no.nav.delta.getEnvVar
import org.flywaydb.core.Flyway

class Database(private val env: Environment) :
    DatabaseInterface {
    private val dataSource: HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = getEnvVar("NAIS_DATABASE_DELTA_BACKEND_DELTA_JDBC_URL", null)
            maximumPoolSize = 3
            minimumIdle = 3
            idleTimeout = 10000
            maxLifetime = 300000
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        },
    )

    override val connection: Connection
        get() = dataSource.connection

    init {
        Flyway.configure().run {
            dataSource(dataSource)
            load().migrate()
        }
    }
}

interface DatabaseInterface {
    val connection: Connection
}
