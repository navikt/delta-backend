ALTER TABLE event
    ADD COLUMN invite_mode TEXT NOT NULL DEFAULT 'PER_PARTICIPANT'
        CHECK (invite_mode IN ('PER_PARTICIPANT', 'SHARED')),
    ADD COLUMN calendar_sync_status TEXT NULL
        CHECK (calendar_sync_status IN ('PENDING', 'SYNCED', 'FAILED')),
    ADD COLUMN calendar_sync_error TEXT NULL;

ALTER TABLE participant
    ADD COLUMN last_response_time TIMESTAMPTZ NULL,
    ADD COLUMN last_response_value TEXT NULL,
    ADD COLUMN invited_by TEXT NULL,
    ADD COLUMN invited_at TIMESTAMPTZ NULL,
    ADD COLUMN status TEXT NOT NULL DEFAULT 'REGISTERED'
        CHECK (status IN ('INVITED', 'REGISTERED', 'DECLINED', 'FORWARDED'));

-- No event foreign key: cancellation and a create acknowledgement must survive deletion.
CREATE TABLE shared_calendar_outbox (
    event_id UUID PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 1,
    graph_event_id TEXT NULL,
    creation_snapshot TEXT NULL,
    cancel BOOLEAN NOT NULL DEFAULT FALSE,
    state TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'SYNCED', 'FAILED')),
    claim_token UUID NULL,
    claimed_revision BIGINT NULL,
    lease_until TIMESTAMPTZ NULL,
    next_attempt TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    attempts INTEGER NOT NULL DEFAULT 0,
    applied_revision BIGINT NOT NULL DEFAULT 0,
    details_revision BIGINT NOT NULL DEFAULT 1,
    applied_details_revision BIGINT NOT NULL DEFAULT 0,
    reconciliation_revision BIGINT NOT NULL DEFAULT 0,
    reconciled_revision BIGINT NOT NULL DEFAULT 0,
    last_reconciliation_queued TIMESTAMPTZ NULL,
    pending_since TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    failed_since TIMESTAMPTZ NULL
);
CREATE TABLE shared_calendar_recipient_daily (
    day DATE PRIMARY KEY,
    estimated_recipients BIGINT NOT NULL CHECK (estimated_recipients >= 0)
);
CREATE INDEX shared_calendar_outbox_pending_idx
    ON shared_calendar_outbox(next_attempt) WHERE state = 'PENDING';

CREATE TABLE shared_calendar_removal (
    event_id UUID NOT NULL REFERENCES shared_calendar_outbox(event_id),
    email TEXT NOT NULL,
    revision BIGINT NOT NULL,
    phase TEXT NOT NULL DEFAULT 'REMOVING' CHECK (phase IN ('REMOVING', 'REMOVED')),
    readd BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(event_id, email)
);

CREATE TABLE shared_calendar_refusal (
    event_id UUID NOT NULL REFERENCES event(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    reason TEXT NOT NULL CHECK (reason IN ('FULL', 'DEADLINE')),
    PRIMARY KEY (event_id, email)
);

CREATE FUNCTION preserve_shared_calendar_cancel() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.invite_mode = 'SHARED' THEN
        INSERT INTO shared_calendar_outbox(event_id, graph_event_id, cancel)
        VALUES (OLD.id, OLD.master_calendar_event_id, TRUE)
        ON CONFLICT(event_id) DO UPDATE
        SET revision = shared_calendar_outbox.revision + 1, cancel = TRUE,
            graph_event_id = COALESCE(shared_calendar_outbox.graph_event_id, OLD.master_calendar_event_id),
            pending_since = CASE WHEN shared_calendar_outbox.state='PENDING'
                THEN shared_calendar_outbox.pending_since ELSE NOW() END,
            failed_since=NULL, state = 'PENDING', next_attempt = NOW();
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER shared_calendar_cancel BEFORE DELETE ON event
    FOR EACH ROW EXECUTE FUNCTION preserve_shared_calendar_cancel();
