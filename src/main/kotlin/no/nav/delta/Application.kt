package no.nav.delta

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import no.nav.delta.application.createApplicationEngine
import no.nav.delta.plugins.*

fun main() {
    val environment = Environment()
    val database = Database(environment)

    createApplicationEngine(environment, database).start(wait = true)
}


