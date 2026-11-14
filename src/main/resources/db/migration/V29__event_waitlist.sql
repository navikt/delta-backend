ALTER TABLE event ADD COLUMN waitlist_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE event_waitlist (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES event(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    name TEXT NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(event_id, email)
);
CREATE INDEX event_waitlist_order_idx ON event_waitlist(event_id, id);

CREATE TABLE waitlist_notification (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES event(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    name TEXT NOT NULL,
    calendar_event_id TEXT,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    state TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX waitlist_notification_due_idx ON waitlist_notification(next_attempt) WHERE state='PENDING';

ALTER TABLE participant ADD COLUMN waitlist_calendar_event_id TEXT;
CREATE TABLE waitlist_calendar_cancellation (
    calendar_event_id TEXT PRIMARY KEY,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    state TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','FAILED'))
);

CREATE FUNCTION preserve_waitlist_calendar_cancellation() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.waitlist_calendar_event_id IS NOT NULL THEN
        INSERT INTO waitlist_calendar_cancellation(calendar_event_id)
        VALUES (OLD.waitlist_calendar_event_id) ON CONFLICT DO NOTHING;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER waitlist_calendar_cancel BEFORE DELETE ON participant
    FOR EACH ROW EXECUTE FUNCTION preserve_waitlist_calendar_cancellation();
