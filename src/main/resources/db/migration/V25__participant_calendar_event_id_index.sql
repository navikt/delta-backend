-- Index for fast participant lookup by calendar event ID on the webhook hot path.
-- calendar_event_id is unique per participant row (one calendar invite per registration).
CREATE INDEX idx_participant_calendar_event_id ON participant (calendar_event_id);
