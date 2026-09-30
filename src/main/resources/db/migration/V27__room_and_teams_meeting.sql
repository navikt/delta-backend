ALTER TABLE event
    ADD COLUMN room_email                TEXT        NULL,
    ADD COLUMN room_name                 TEXT        NULL,
    ADD COLUMN room_status               VARCHAR(20) NULL,
    ADD COLUMN is_online_meeting         BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN teams_join_url            TEXT        NULL,
    ADD COLUMN teams_conference_id       TEXT        NULL,
    ADD COLUMN teams_dial_in             TEXT        NULL,
    ADD COLUMN master_calendar_event_id  TEXT        NULL;

-- Looked up by the webhook to recognise notifications for the room/Teams "master" event
-- (see docs/teams-meeting-room-booking-plan.md).
CREATE INDEX event_master_calendar_event_id_idx
    ON event (master_calendar_event_id)
    WHERE master_calendar_event_id IS NOT NULL;
