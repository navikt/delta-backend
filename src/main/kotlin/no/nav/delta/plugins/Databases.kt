package no.nav.delta.plugins

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.pool.HikariPool
import java.net.ConnectException
import java.net.SocketException
import java.sql.Connection
import java.sql.ResultSet
import no.nav.delta.Environment
import org.flywaydb.core.Flyway

class Database(private val env: Environment, retries: Long = 30, sleepTime: Long = 10_000) :
    DatabaseInterface {
    private val dataSource: HikariDataSource

    override val connection: Connection
        get() = dataSource.connection

    init {
        var current = 0
        var connected = false
        var tempDatasource: HikariDataSource? = null
        while (!connected && current++ < retries) {
            try {
                tempDatasource =
                    HikariDataSource(
                        HikariConfig().apply {
                            jdbcUrl = env.jdbcUrl()
                            username = env.dbUsername
                            password = env.dbPassword
                            maximumPoolSize = 200
                            minimumIdle = 3
                            idleTimeout = 10000
                            maxLifetime = 300000
                            isAutoCommit = false
                            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                            validate()
                        },
                    )
                connected = true
            } catch (ex: HikariPool.PoolInitializationException) {
                if (ex.cause?.cause is ConnectException || ex.cause?.cause is SocketException) {
                    Thread.sleep(sleepTime)
                } else {
                    throw ex
                }
            }
        }
        if (tempDatasource == null) {
            throw RuntimeException("Could not connect to DB")
        }
        dataSource = tempDatasource
        runFlywayMigrations()
    }

    private fun runFlywayMigrations() =
        Flyway.configure().run {
            locations("db")
            configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            dataSource(env.jdbcUrl(), env.dbUsername, env.dbPassword)
            load().migrate()
        }
}

fun <T> ResultSet.toList(mapper: ResultSet.() -> T) =
    mutableListOf<T>().apply {
        while (next()) {
            add(mapper())
        }
    }

fun <T> ResultSet.toSet(mapper: ResultSet.() -> T) =
    mutableSetOf<T>().apply {
        while (next()) {
            add(mapper())
        }
    }

interface DatabaseInterface {
    val connection: Connection
}
