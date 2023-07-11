package no.nav.delta.event

import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.plugins.toList
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.util.UUID

fun DatabaseInterface.addEvent(
    ownerEmail: String,
    title: String,
    description: String,
    startTime: Timestamp,
    endTime: Timestamp,
    location: String?,
): Event {
    val out: Event
    connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement(
"""
INSERT INTO event(owner, title, description, start_time, end_time, location)
    VALUES (?, ?, ?, ?, ?, ?)
RETURNING *;
""",
            )

        preparedStatement.setString(1, ownerEmail)
        preparedStatement.setString(2, title)
        preparedStatement.setString(3, description)
        preparedStatement.setTimestamp(4, startTime)
        preparedStatement.setTimestamp(5, endTime)
        preparedStatement.setString(6, location)

        val result = preparedStatement.executeQuery()
        result.next()
        out = result.resultSetToEvent()
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
        return result.resultSetToEvent()
    }
}

fun DatabaseInterface.getFutureEvents(): List<Event> {
    val events: MutableList<Event>
    connection.use { connection ->
        val preparedStatement = connection.prepareStatement("SELECT * FROM event WHERE end_time > now();")
        val result = preparedStatement.executeQuery()
        events = result.toList { resultSetToEvent() }
    }
    return events
}

fun DatabaseInterface.getEventsByOwner(ownerEmail: String): List<Event> {
    val events: MutableList<Event>
    connection.use { connection ->
        val preparedStatement = connection.prepareStatement("SELECT * FROM event WHERE owner=?")
        preparedStatement.setString(1, ownerEmail)
        val result = preparedStatement.executeQuery()
        events = result.toList { resultSetToEvent() }
    }
    return events
}

fun DatabaseInterface.registerForEvent(eventId: String, email: String): UUID? {
    val otp: String
    connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement(
                "INSERT INTO participant(event_id, email) VALUES (uuid(?), ?) RETURNING otp;",
            )
        preparedStatement.setString(1, eventId)
        preparedStatement.setString(2, email)

        val result: ResultSet
        try {
            result = preparedStatement.executeQuery()
        } catch (e: SQLException) {
            return null
        }

        if (!result.next()) {
            return null
        }
        otp = result.getString("otp")
        connection.commit()
    }
    return UUID.fromString(otp)
}

fun DatabaseInterface.alreadyRegisteredForEvent(eventId: String, email: String): Boolean {
    val registered: Boolean
    connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement(
                "SELECT * FROM participant WHERE event_id=uuid(?) AND email=?;",
            )
        preparedStatement.setString(1, eventId)
        preparedStatement.setString(2, email)

        val result = preparedStatement.executeQuery()
        registered = result.next()
    }
    return registered
}

fun DatabaseInterface.unregisterFromEvent(eventId: String, otp: String): Boolean {
    val rowsAffected: Int
    connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement(
                "DELETE FROM participant WHERE event_id=uuid(?) AND otp=uuid(?);",
            )
        preparedStatement.setString(1, eventId)
        preparedStatement.setString(2, otp)

        rowsAffected = preparedStatement.executeUpdate()
        connection.commit()
    }
    return rowsAffected > 0
}

fun ResultSet.resultSetToEvent(): Event {
    return Event(
        id = UUID.fromString(getString("id")),
        ownerEmail = getString("owner"),
        title = getString("title"),
        description = getString("description"),
        startTime = getTimestamp("start_time"),
        endTime = getTimestamp("end_time"),
        location = getString("location"),
    )
}
