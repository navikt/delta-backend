-- Enforce single active subscription per resource (mailbox).
-- Remove any duplicate rows first, keeping the one with the latest expiration_time.
DELETE FROM graph_subscription
WHERE id NOT IN (
    SELECT DISTINCT ON (resource) id
    FROM graph_subscription
    ORDER BY resource, expiration_time DESC
);

ALTER TABLE graph_subscription
    ADD CONSTRAINT graph_subscription_resource_unique UNIQUE (resource);
