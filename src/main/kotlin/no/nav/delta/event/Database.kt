package no.nav.delta.event

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.plugins.toList

fun DatabaseInterface.addEvent(
    ownerEmail: String,
    title: String,
    description: String,
    startTime: Timestamp,
    endTime: Timestamp,
    location: String?,
): Event {
    return connection.use { connection ->
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
        connection.commit()
        result.next()
        result.toEvent()
    }
}

fun DatabaseInterface.getEvent(id: String): Either<EventNotFoundException, Event> {
    return connection.use { connection ->
        val preparedStatement = connection.prepareStatement("SELECT * FROM event WHERE id=uuid(?)")
        preparedStatement.setString(1, id)
        val result = preparedStatement.executeQuery()

        if (!result.next()) EventNotFoundException.left() else result.toEvent().right()
    }
}

fun DatabaseInterface.getParticipants(
    id: String
): Either<EventNotFoundException, List<Participant>> {
    return connection.use { connection ->
        checkIfEventExists(connection, id).flatMap {
            val preparedStatement =
                connection.prepareStatement("SELECT email FROM participant WHERE event_id=uuid(?);")
            preparedStatement.setString(1, id)
            val result = preparedStatement.executeQuery()
            result.toList { Participant(email = getString(1)) }.right()
        }
    }
}

fun DatabaseInterface.getFutureEvents(): List<Event> {
    return connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement("SELECT * FROM event WHERE end_time > now() ORDER BY start_time;")
        val result = preparedStatement.executeQuery()
        result.toList { toEvent() }
    }
}

fun DatabaseInterface.getEventsByOwner(ownerEmail: String): List<Event> {
    return connection.use { connection ->
        val preparedStatement = connection.prepareStatement("SELECT * FROM event WHERE owner=? ORDER BY start_time;")
        preparedStatement.setString(1, ownerEmail)
        val result = preparedStatement.executeQuery()
        result.toList { toEvent() }
    }
}

fun DatabaseInterface.registerForEvent(
    eventId: String,
    email: String
): Either<RegisterForEventError, Unit> {
    return connection.use { connection ->
        checkIfEventExists(connection, eventId)
            .flatMap { checkIfParticipantIsRegistered(connection, eventId, email) }
            .flatMap {
                val preparedStatement =
                    connection.prepareStatement(
                        "INSERT INTO participant(event_id, email) VALUES (uuid(?), ?);",
                    )
                preparedStatement.setString(1, eventId)
                preparedStatement.setString(2, email)

                preparedStatement.executeUpdate()
                connection.commit()
                Unit.right()
            }
    }
}

fun DatabaseInterface.unregisterFromEvent(
    eventId: String,
    email: String
): Either<UnregisterFromEventError, Unit> {
    return connection.use { connection ->
        checkIfEventExists(connection, eventId).flatMap {
            val preparedStatement =
                connection.prepareStatement(
                    "DELETE FROM participant WHERE event_id=uuid(?) AND email=?;",
                )
            preparedStatement.setString(1, eventId)
            preparedStatement.setString(2, email)

            val rowsAffected = preparedStatement.executeUpdate()
            connection.commit()
            if (rowsAffected == 0) EmailNotFoundException.left() else Unit.right()
        }
    }
}

fun DatabaseInterface.getJoinedEvents(email: String): List<Event> {
    return connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement(
                "SELECT * FROM event JOIN participant p on event.id = p.event_id WHERE email=? ORDER BY start_time;")
        preparedStatement.setString(1, email)

        val result = preparedStatement.executeQuery()
        result.toList { toEvent() }
    }
}

fun DatabaseInterface.deleteEvent(id: String): Either<EventNotFoundException, Unit> {
    return connection.use { connection ->
        val preparedStatement = connection.prepareStatement("DELETE FROM event WHERE id=uuid(?);")
        preparedStatement.setString(1, id)

        val result = preparedStatement.executeUpdate()
        connection.commit()

        if (result == 0) EventNotFoundException.left() else Unit.right()
    }
}

fun DatabaseInterface.updateEvent(newEvent: Event): Either<EventNotFoundException, Event> {
    return connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement(
                """
UPDATE event 
    SET title=?, description=?, start_time=?, end_time=?, location=? 
    WHERE id=uuid(?) RETURNING *;
""")
        preparedStatement.setString(1, newEvent.title)
        preparedStatement.setString(2, newEvent.description)
        preparedStatement.setTimestamp(3, Timestamp.from(newEvent.startTime.toInstant()))
        preparedStatement.setTimestamp(4, Timestamp.from(newEvent.endTime.toInstant()))
        preparedStatement.setString(5, newEvent.location)
        preparedStatement.setString(6, newEvent.id.toString())

        val result = preparedStatement.executeQuery()
        connection.commit()

        if (!result.next()) EventNotFoundException.left() else result.toEvent().right()
    }
}

fun ResultSet.toEvent(): Event {
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

fun checkIfEventExists(
    connection: Connection,
    eventId: String
): Either<EventNotFoundException, Unit> {
    return connection.prepareStatement("SELECT * FROM event WHERE id=uuid(?);").use {
        preparedStatement ->
        preparedStatement.setString(1, eventId)

        val result = preparedStatement.executeQuery()
        if (!result.next()) EventNotFoundException.left() else Either.Right(Unit)
    }
}

fun checkIfParticipantIsRegistered(
    connection: Connection,
    eventId: String,
    email: String
): Either<ParticipantAlreadyRegisteredException, Unit> {
    return connection
        .prepareStatement("SELECT * FROM participant WHERE event_id=uuid(?) AND email=?;")
        .use { preparedStatement ->
            preparedStatement.setString(1, eventId)
            preparedStatement.setString(2, email)

            val result = preparedStatement.executeQuery()
            if (result.next()) ParticipantAlreadyRegisteredException.left() else Unit.right()
        }
}

