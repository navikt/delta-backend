package no.nav.delta.plugins

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import no.nav.delta.Environment
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

class DatabaseConfig(private val env: Environment) : DatabaseInterface {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val hikariConfig = HikariConfig().apply {
        if(env.azureAppClientId.isBlank()) {
            logger.info("Setting up local database connection with jdbcUrl = ${env.dbJdbcUrl}")
        }
        jdbcUrl = env.dbJdbcUrl
    }


    private val dataSource: HikariDataSource = HikariDataSource(hikariConfig)

    override val connection: Connection
        get() = dataSource.connection

    init {
        /*Flyway.configure().run {
            dataSource(dataSource)
            locations("db/migration")
            load().migrate()
        }*/
    }
}

interface DatabaseInterface {
    val connection: Connection
}
