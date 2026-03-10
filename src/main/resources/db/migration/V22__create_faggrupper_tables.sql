CREATE TABLE faggrupper
(
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    navn             VARCHAR     NOT NULL,
    beskrivelse      TEXT,
    undertittel      VARCHAR,
    malgruppe        VARCHAR,
    type             VARCHAR     NOT NULL,
    tidspunkt        VARCHAR,
    starttid         TIME,
    sluttid          TIME,
    slack_kanal_navn VARCHAR,
    slack_kanal_url  VARCHAR,
    er_aktiv         BOOLEAN     NOT NULL DEFAULT true,
    opprettet        TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE TABLE faggruppe_eiere
(
    id           UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    faggruppe_id UUID    NOT NULL REFERENCES faggrupper (id) ON DELETE CASCADE,
    epost        VARCHAR NOT NULL,
    navn         VARCHAR,
    CONSTRAINT faggruppe_eiere_faggruppe_id_epost_key UNIQUE (faggruppe_id, epost)
);

CREATE INDEX idx_faggruppe_eiere_faggruppe_id ON faggruppe_eiere (faggruppe_id);
