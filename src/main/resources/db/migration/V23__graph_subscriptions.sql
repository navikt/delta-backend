CREATE TABLE graph_subscription
(
    id              BIGSERIAL PRIMARY KEY,
    subscription_id VARCHAR(255) NOT NULL UNIQUE,
    resource        TEXT         NOT NULL,
    expiration_time TIMESTAMP    NOT NULL,
    client_state    VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);
