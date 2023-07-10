package no.nav.delta.event

import no.nav.delta.plugins.DatabaseInterface
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

fun DatabaseInterface.getEvents(): List<Event> {
    val events = ArrayList<Event>()
    connection.use {
        it.prepareStatement("SELECT * FROM event;")
            .use {
                it.executeQuery().use {
                    while (it.next()) {
                        val event = resultSetToEvent(it)
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

fun DatabaseInterface.getEvent(id: String): Event? {
    connection.use {
        it.prepareStatement("SELECT * FROM event WHERE id=uuid(?)")
            .use {
                it.setString(1, id)
                val result = it.executeQuery()

                if (!result.next()) return null
                return resultSetToEvent(result)
            }
    }
}

fun resultSetToEvent(resultSet: ResultSet): Event {
    return Event(
        id = UUID.fromString(resultSet.getString("id")),
        ownerEmail = resultSet.getString("owner"),
        title = resultSet.getString("title"),
        description = resultSet.getString("description"),
        startTime = resultSet.getTimestamp("start_time"),
        endTime = resultSet.getTimestamp("end_time"),
    )
}