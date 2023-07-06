package no.nav.delta.endpoints

import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.delta.plugins.DatabaseInterface
import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util.*


data class Event (
    val id: Int,
    val ownerEmail: String,
    val title: String,
    val description: String,
    val startTime: Date,
    val endTime: Date,
)

data class CreateEvent (
    val title: String,
    val description: String,
    val startTime: Date,
    val endTime: Date,
)

fun DatabaseInterface.getEvents(): List<Event> {
    val events = ArrayList<Event>()
    connection.use {
        it.prepareStatement("SELECT * FROM event;")
            .use {
                it.executeQuery().use {
                    while (it.next()) {
                        val event = Event(
                            id = it.getInt("id"),
                            ownerEmail = it.getString("owner"),
                            title = it.getString("title"),
                            description = it.getString("description"),
                            startTime = it.getDate("start_time"),
                            endTime = it.getDate("end_time"),
                        )
                        events.add(event)
                    }
                }
            }
    }
    return events
}

fun DatabaseInterface.addEvent(ownerEmail: String, title: String, description: String, startTime: Timestamp, endTime: Timestamp) {
    connection.use {
        it.prepareStatement("INSERT INTO event(owner, title, description, start_time, end_time) VALUES (?, ?, ?, ?, ?);")
            .use {
                it.setString(1, ownerEmail)
                it.setString(2, title)
                it.setString(3, description)
                it.setTimestamp(4, startTime)
                it.setTimestamp(5, endTime)
                it.executeUpdate()
            }
        it.commit()
    }
}

fun Route.eventApi(database: DatabaseInterface) {
    route("/event") {
        accept(ContentType.Application.Json) {
            get {
                call.respond(database.getEvents())
            }
        }
    }

    authenticate("jwt")  {
        route("/admin/event") {
            put {
                val createEvent = call.receive(CreateEvent::class)

                val principal = call.principal<JWTPrincipal>()!!
                val ownerEmail = principal["preferred_username"]!!.lowercase()
                println(createEvent.startTime)
                println(createEvent.startTime.toInstant())

                database.addEvent(
                    ownerEmail,
                    createEvent.title,
                    createEvent.description,
                    Timestamp.from(createEvent.startTime.toInstant()),
                    Timestamp.from(createEvent.endTime.toInstant()),
                )
                call.respond("success")
            }
        }
    }
}