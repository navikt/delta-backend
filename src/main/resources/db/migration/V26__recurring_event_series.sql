CREATE TABLE recurring_event_series (
    id                             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title                          TEXT        NOT NULL,
    description                    TEXT        NOT NULL DEFAULT '',
    location                       TEXT        NOT NULL,
    public                         BOOLEAN     NOT NULL,
    participant_limit              INT         NOT NULL,
    recurrence_frequency           VARCHAR(20) NOT NULL,
    start_date                     DATE        NOT NULL,
    until_date                     DATE        NOT NULL,
    occurrence_duration_minutes    BIGINT      NOT NULL,
    signup_deadline_offset_minutes BIGINT,
    created_by_email               TEXT        NOT NULL,
    created_at                     TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE TABLE recurring_event_occurrence (
    series_id         UUID    NOT NULL REFERENCES recurring_event_series (id) ON DELETE CASCADE,
    event_id          UUID    NOT NULL UNIQUE REFERENCES event (id) ON DELETE CASCADE,
    occurrence_index  INT     NOT NULL,
    occurrence_date   DATE    NOT NULL,
    PRIMARY KEY (series_id, occurrence_index)
);

CREATE TABLE recurring_event_series_category (
    series_id   UUID NOT NULL REFERENCES recurring_event_series (id) ON DELETE CASCADE,
    category_id INT  NOT NULL REFERENCES category (id),
    PRIMARY KEY (series_id, category_id)
);

CREATE INDEX recurring_event_occurrence_event_id_idx
    ON recurring_event_occurrence (event_id);

CREATE INDEX recurring_event_occurrence_series_date_idx
    ON recurring_event_occurrence (series_id, occurrence_date);
