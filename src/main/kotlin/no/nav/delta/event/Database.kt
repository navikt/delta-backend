package no.nav.delta.event

import no.nav.delta.plugins.DatabaseInterface
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

fun DatabaseInterface.getFutureEvents(): List<Event> {
    val events = ArrayList<Event>()
    connection.use { connection ->
        val preparedStatement = connection.prepareStatement("SELECT * FROM event WHERE end_time > now();")
        val result = preparedStatement.executeQuery()
        while (result.next()) {
            val event = resultSetToEvent(result)
            events.add(event)
        }
    }
    return events
}

fun DatabaseInterface.addEvent(
    ownerEmail: String,
    title: String,
    description: String,
    startTime: Timestamp,
    endTime: Timestamp,
): Event {
    lateinit var out: Event
    connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement(
"""
INSERT INTO event(owner, title, description, start_time, end_time)
    VALUES (?, ?, ?, ?, ?)
RETURNING *;
""",
            )

        preparedStatement.setString(1, ownerEmail)
        preparedStatement.setString(2, title)
        preparedStatement.setString(3, description)
        preparedStatement.setTimestamp(4, startTime)
        preparedStatement.setTimestamp(5, endTime)

        preparedStatement.executeQuery().use {
            it.next()
            out = resultSetToEvent(it)
        }
        connection.commit()
    }

    return out
}

fun DatabaseInterface.getEvent(id: String): Event? {
    connection.use { connection ->
        val preparedStatement = connection.prepareStatement("SELECT * FROM event WHERE id=uuid(?)")
        preparedStatement.setString(1, id)
        val result = preparedStatement.executeQuery()

        if (!result.next()) return null
        return resultSetToEvent(result)
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
