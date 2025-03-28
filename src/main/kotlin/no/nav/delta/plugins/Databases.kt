package no.nav.delta.plugins

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import no.nav.delta.Environment
import org.flywaydb.core.Flyway

class DatabaseConfig(private val env: Environment) : DatabaseInterface {
    private val hikariConfig = HikariConfig().apply {
        jdbcUrl = env.dbJdbcUrl
        username = env.dbUsername
        password = env.dbPassword
        maximumPoolSize = 3
        minimumIdle = 3
        idleTimeout = 10000
        maxLifetime = 300000
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        validate()
    }


    private val dataSource: HikariDataSource = HikariDataSource(hikariConfig)

    override val connection: Connection
        get() = dataSource.connection

    init {
        Flyway.configure().run {
            dataSource(env.dbJdbcUrl, env.dbUsername, env.dbPassword)
            locations("db/migration")
            load().migrate()
        }
    }
}

interface DatabaseInterface {
    val connection: Connection
}
