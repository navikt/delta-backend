package no.nav.delta.event

import arrow.core.Either
import arrow.core.Option
import arrow.core.flatMap
import arrow.core.left
import arrow.core.none
import arrow.core.right
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import no.nav.delta.plugins.DatabaseInterface
import no.nav.delta.plugins.toList

fun DatabaseInterface.addEvent(
    ownerEmail: String,
    createEvent: CreateEvent,
): Event {
    return connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement(
                """
 INSERT INTO event
            (
                        owner,
                        title,
                        description,
                        start_time,
                        end_time,
                        location,
                        public,
                        participant_limit,
                        signup_deadline
            )
            VALUES
            (
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?
            )
            returning *;
""")

        preparedStatement.setString(1, ownerEmail)
        preparedStatement.setString(2, createEvent.title)
        preparedStatement.setString(3, createEvent.description)
        preparedStatement.setTimestamp(4, Timestamp.valueOf(createEvent.startTime))
        preparedStatement.setTimestamp(5, Timestamp.valueOf(createEvent.endTime))
        preparedStatement.setString(6, createEvent.location)
        preparedStatement.setBoolean(7, createEvent.public)
        preparedStatement.setInt(8, createEvent.participantLimit)
        preparedStatement.setTimestamp(
            9, createEvent.signupDeadline?.let { Timestamp.valueOf(it) })

        val result = preparedStatement.executeQuery()
        connection.commit()
        result.next()
        result.toEvent()
    }
}

fun DatabaseInterface.getEvent(id: String): Either<EventNotFoundException, Event> {
    return connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement("""
SELECT *
FROM   event
WHERE  id = Uuid(?);
""")
        preparedStatement.setString(1, id)
        val result = preparedStatement.executeQuery()

        if (!result.next()) EventNotFoundException.left() else result.toEvent().right()
    }
}

fun DatabaseInterface.getParticipants(
    id: String
): Either<EventNotFoundException, List<Participant>> {
    return connection.use { connection ->
        checkIfEventExists(connection, id).map {
            val preparedStatement =
                connection.prepareStatement(
                    """
SELECT email
FROM   participant
WHERE  event_id = Uuid(?);
""")
            preparedStatement.setString(1, id)
            val result = preparedStatement.executeQuery()
            result.toList { Participant(email = getString(1)) }
        }
    }
}

fun DatabaseInterface.getEvents(
    onlyFuture: Boolean = false,
    onlyPublic: Boolean = false,
    byOwner: Option<String> = none(),
    joinedBy: Option<String> = none(),
): List<Event> {
    return connection.use { connection ->
        val clauses = mutableListOf("TRUE")
        val values = mutableListOf<PreparedStatement.(Int) -> Unit>()

        if (onlyFuture) clauses.add("start_time > NOW()")
        if (onlyPublic) clauses.add("public = TRUE")
        byOwner.onSome { owner ->
            clauses.add("owner = ?")
            values.add { setString(it, owner) }
        }
        joinedBy.onSome { joinedBy ->
            clauses.add("id IN (SELECT event_id FROM participant WHERE email = ?)")
            values.add { setString(it, joinedBy) }
        }

        val preparedStatement =
            connection.prepareStatement(
                "SELECT * FROM event WHERE ${clauses.joinToString(" AND ")} ORDER BY start_time;")
        values.forEachIndexed { index, setSomething -> preparedStatement.setSomething(index + 1) }

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
            .flatMap { checkIfEventIsFull(connection, eventId) }
            .flatMap { checkIfDeadlineIsPassed(connection, eventId) }
            .map {
                val preparedStatement =
                    connection.prepareStatement(
                        """
INSERT INTO participant
            (event_id,
             email)
VALUES      (Uuid(?),
             ?);
""")
                preparedStatement.setString(1, eventId)
                preparedStatement.setString(2, email)

                preparedStatement.executeUpdate()
                connection.commit()
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
                    """
DELETE FROM participant
WHERE  event_id = Uuid(?)
       AND email = ?;
""")
            preparedStatement.setString(1, eventId)
            preparedStatement.setString(2, email)

            val rowsAffected = preparedStatement.executeUpdate()
            connection.commit()
            if (rowsAffected == 0) EmailNotFoundException.left() else Unit.right()
        }
    }
}

fun DatabaseInterface.deleteEvent(id: String): Either<EventNotFoundException, Unit> {
    return connection.use { connection ->
        val preparedStatement =
            connection.prepareStatement("""
DELETE FROM event
WHERE  id = Uuid(?);
""")
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
SET    title=?,
       description=?,
       start_time=?,
       end_time=?,
       location=?,
       public=?,
       participant_limit=?,
       signup_deadline=?
WHERE  id=Uuid(?) returning *;
""")
        preparedStatement.setString(1, newEvent.title)
        preparedStatement.setString(2, newEvent.description)
        preparedStatement.setTimestamp(3, Timestamp.valueOf(newEvent.startTime))
        preparedStatement.setTimestamp(4, Timestamp.valueOf(newEvent.endTime))
        preparedStatement.setString(5, newEvent.location)
        preparedStatement.setBoolean(6, newEvent.public)
        preparedStatement.setInt(7, newEvent.participantLimit)
        preparedStatement.setTimestamp(
            8, newEvent.signupDeadline?.let { Timestamp.valueOf(it) })
        preparedStatement.setString(9, newEvent.id.toString())

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
        startTime = getTimestamp("start_time").toLocalDateTime(),
        endTime = getTimestamp("end_time").toLocalDateTime(),
        location = getString("location"),
        public = getBoolean("public"),
        participantLimit = getInt("participant_limit"),
        signupDeadline = getTimestamp("signup_deadline")?.toLocalDateTime(),
    )
}

fun checkIfEventExists(
    connection: Connection,
    eventId: String
): Either<EventNotFoundException, Unit> {
    return connection.prepareStatement("""
SELECT *
FROM   event
WHERE  id = Uuid(?);
""").use {
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
        .prepareStatement(
            """
SELECT *
FROM   participant
WHERE  event_id = Uuid(?)
       AND email = ?;
""")
        .use { preparedStatement ->
            preparedStatement.setString(1, eventId)
            preparedStatement.setString(2, email)

            val result = preparedStatement.executeQuery()
            if (result.next()) ParticipantAlreadyRegisteredException.left() else Unit.right()
        }
}

fun checkIfEventIsFull(
    connection: Connection,
    eventId: String,
): Either<EventFullException, Unit> {
    return connection
        .prepareStatement(
            """
SELECT e.participant_limit,
       Count(p.event_id) AS participants_count
FROM   event e
       LEFT JOIN participant p
              ON p.event_id = e.id
WHERE  e.id = Uuid(?)
GROUP  BY e.participant_limit
HAVING Count(p.event_id) >= e.participant_limit
       AND e.participant_limit > 0;
""")
        .use { preparedStatement ->
            preparedStatement.setString(1, eventId)

            val result = preparedStatement.executeQuery()
            if (result.next()) EventFullException.left() else Unit.right()
        }
}

fun checkIfDeadlineIsPassed(
    connection: Connection,
    eventId: String,
): Either<DeadlinePassedException, Unit> {
    return connection
        .prepareStatement(
            """
SELECT signup_deadline
FROM event
WHERE id = Uuid(?)
  AND signup_deadline <= NOW();
""") // if signup_deadline is null, it will be false, so no need to check for null
        .use { preparedStatement ->
            preparedStatement.setString(1, eventId)

            val result = preparedStatement.executeQuery()
            if (result.next()) DeadlinePassedException.left() else Unit.right()
        }
}
