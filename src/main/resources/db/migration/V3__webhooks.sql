-- Downstream systems that are sent punch events over HTTP. The retry state
-- lives here and not on each event: an endpoint is either reachable or it is
-- not, and everything queued for it waits behind the same pause.
CREATE TABLE webhook_subscription (
    id                    UUID          PRIMARY KEY,
    url                   VARCHAR(2000) NOT NULL,
    description           VARCHAR(200),
    -- The signing secret itself, not a hash: computing an HMAC needs the secret.
    secret                VARCHAR(80)   NOT NULL,
    status                VARCHAR(16)   NOT NULL,
    consecutive_failures  INTEGER       NOT NULL DEFAULT 0,
    -- Also the dispatcher's lease: a claimed subscription has this pushed into
    -- the future until the attempt is recorded.
    next_attempt_at       TIMESTAMPTZ   NOT NULL,
    last_error            VARCHAR(300),
    last_delivered_at     TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_webhook_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

-- One row per event and subscription, in the order they are to be sent. The
-- unique constraint is what makes the fan-out idempotent: an event consumed
-- twice from Kafka is queued once.
CREATE TABLE webhook_delivery (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subscription_id  UUID         NOT NULL REFERENCES webhook_subscription (id) ON DELETE CASCADE,
    event_id         UUID         NOT NULL,
    payload          TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    delivered_at     TIMESTAMPTZ,
    CONSTRAINT uq_webhook_delivery UNIQUE (subscription_id, event_id)
);

CREATE INDEX ix_webhook_delivery_pending ON webhook_delivery (subscription_id, id) WHERE delivered_at IS NULL;
