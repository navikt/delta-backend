-- Organizer-defined signup options with independent capacity. Events without rows here keep the
-- event-level participant_limit/waitlist_enabled allocation model, so existing events are unchanged.
CREATE TABLE event_signup_option (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES event(id) ON DELETE CASCADE,
    -- Shared by the corresponding options of every occurrence in a recurring series.
    series_key UUID NOT NULL,
    label TEXT NOT NULL CHECK (length(btrim(label)) BETWEEN 1 AND 100),
    participant_limit INTEGER NOT NULL DEFAULT 0 CHECK (participant_limit >= 0),
    waitlist_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    position INTEGER NOT NULL,
    CHECK (NOT waitlist_enabled OR participant_limit > 0),
    UNIQUE (event_id, id),
    UNIQUE (event_id, series_key)
);
CREATE INDEX event_signup_option_order_idx ON event_signup_option(event_id, position);

-- Composite keys guarantee that a selected option belongs to the same event.
ALTER TABLE participant
    ADD COLUMN signup_option_id UUID,
    ADD CONSTRAINT participant_signup_option_fk FOREIGN KEY (event_id, signup_option_id)
        REFERENCES event_signup_option(event_id, id);
CREATE INDEX participant_signup_option_idx ON participant(event_id, signup_option_id);

ALTER TABLE event_waitlist
    ADD COLUMN signup_option_id UUID,
    ADD CONSTRAINT event_waitlist_signup_option_fk FOREIGN KEY (event_id, signup_option_id)
        REFERENCES event_signup_option(event_id, id);
CREATE INDEX event_waitlist_option_order_idx ON event_waitlist(event_id, signup_option_id, id);

ALTER TABLE shared_calendar_refusal DROP CONSTRAINT shared_calendar_refusal_reason_check;
ALTER TABLE shared_calendar_refusal
    ADD CONSTRAINT shared_calendar_refusal_reason_check
        CHECK (reason IN ('FULL', 'DEADLINE', 'OPTION_REQUIRED'));
