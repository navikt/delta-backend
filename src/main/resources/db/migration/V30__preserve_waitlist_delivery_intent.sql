-- Like shared_calendar_outbox, creation recovery must survive event deletion.
ALTER TABLE waitlist_notification DROP CONSTRAINT waitlist_notification_event_id_fkey;
ALTER TABLE waitlist_notification
    ADD COLUMN creation_snapshot TEXT,
    ADD COLUMN cancel BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE participant ADD COLUMN waitlist_notification_id UUID;

UPDATE participant p SET waitlist_notification_id=(
    SELECT n.id FROM waitlist_notification n
    WHERE n.event_id=p.event_id AND n.email=p.email ORDER BY n.created_at DESC,n.id LIMIT 1
);

CREATE OR REPLACE FUNCTION preserve_waitlist_calendar_cancellation() RETURNS TRIGGER AS $$
BEGIN
    UPDATE waitlist_notification SET cancel=TRUE,state='PENDING',attempts=0,next_attempt=NOW()
    WHERE id=OLD.waitlist_notification_id AND NOT cancel;
    IF OLD.waitlist_calendar_event_id IS NOT NULL THEN
        INSERT INTO waitlist_calendar_cancellation(calendar_event_id)
        VALUES (OLD.waitlist_calendar_event_id) ON CONFLICT DO NOTHING;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION preserve_waitlist_delivery_cancellation() RETURNS TRIGGER AS $$
BEGIN
    UPDATE waitlist_notification SET cancel=TRUE,state='PENDING',attempts=0,next_attempt=NOW()
    WHERE event_id=OLD.id AND NOT cancel;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER waitlist_delivery_cancel BEFORE DELETE ON event
    FOR EACH ROW EXECUTE FUNCTION preserve_waitlist_delivery_cancellation();
