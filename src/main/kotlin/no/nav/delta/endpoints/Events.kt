package no.nav.delta.endpoints

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.delta.plugins.DatabaseInterface
import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util.*


data class Event (
    val id: Int,
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

fun DatabaseInterface.addEvent(event: Event) {
    connection.use {
        it.prepareStatement("INSERT INTO event(title, description, start_time, end_time) VALUES (?, ?, ?, ?);")
            .use {
                it.setString(1, event.title)
                it.setString(2, event.description)
                it.setTimestamp(3, Timestamp.from(event.startTime.toInstant()))
                it.setTimestamp(4, Timestamp.from(event.endTime.toInstant()))
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
            put {
                val event = call.receive(Event::class)
                database.addEvent(event)
                call.respond("success")
            }
        }
    }
}