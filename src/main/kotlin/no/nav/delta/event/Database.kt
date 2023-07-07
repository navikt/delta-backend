package no.nav.delta.event

import no.nav.delta.plugins.DatabaseInterface
import java.sql.Timestamp

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
                            startTime = it.getTimestamp("start_time"),
                            endTime = it.getTimestamp("end_time"),
                        )
                        events.add(event)
                    }
                }
            }
    }
    return events
}

fun DatabaseInterface.addEvent(
    ownerEmail: String,
    title: String,
    description: String,
    startTime: Timestamp,
    endTime: Timestamp
) {
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