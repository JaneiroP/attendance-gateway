CREATE TABLE device (
    id            UUID         PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    site_code     VARCHAR(40)  NOT NULL,
    time_zone     VARCHAR(60)  NOT NULL,
    -- SHA-256 hex of the API key. The key itself is shown once and never stored.
    key_hash      VARCHAR(64)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    last_seen_at  TIMESTAMPTZ,
    CONSTRAINT ck_device_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX ix_device_site ON device (site_code);

-- Clocks know people by their own enrollment code, not by the HR employee id.
-- The code is only unique within a site.
CREATE TABLE employee_mapping (
    id           UUID         PRIMARY KEY,
    site_code    VARCHAR(40)  NOT NULL,
    user_code    VARCHAR(40)  NOT NULL,
    employee_id  VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_employee_mapping UNIQUE (site_code, user_code)
);

CREATE TABLE punch (
    id                UUID         PRIMARY KEY,
    device_id         UUID         NOT NULL REFERENCES device (id),
    site_code         VARCHAR(40)  NOT NULL,
    user_code         VARCHAR(40)  NOT NULL,
    employee_id       VARCHAR(64)  NOT NULL,
    -- Wall-clock time exactly as the device reported it: this is what makes a
    -- retried punch recognizable as the same punch.
    punched_at_local  TIMESTAMP    NOT NULL,
    punched_at_utc    TIMESTAMPTZ  NOT NULL,
    type              VARCHAR(8)   NOT NULL,
    received_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_punch_type CHECK (type IN ('IN', 'OUT')),
    -- The idempotency key. Enforced by the database so that two copies of a
    -- batch arriving at the same time cannot both insert.
    CONSTRAINT uq_punch_identity UNIQUE (device_id, user_code, punched_at_local, type)
);

CREATE INDEX ix_punch_employee_time ON punch (employee_id, punched_at_utc);

-- Punches that could not be accepted are kept with the reason, never dropped.
CREATE TABLE quarantined_punch (
    id                UUID         PRIMARY KEY,
    device_id         UUID         NOT NULL REFERENCES device (id),
    site_code         VARCHAR(40)  NOT NULL,
    user_code         VARCHAR(40)  NOT NULL,
    punched_at_local  TIMESTAMP    NOT NULL,
    type              VARCHAR(8)   NOT NULL,
    reason            VARCHAR(32)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    received_at       TIMESTAMPTZ  NOT NULL,
    resolved_at       TIMESTAMPTZ,
    CONSTRAINT ck_quarantine_type CHECK (type IN ('IN', 'OUT')),
    CONSTRAINT ck_quarantine_reason CHECK (reason IN ('UNKNOWN_EMPLOYEE', 'FUTURE_TIMESTAMP')),
    CONSTRAINT ck_quarantine_status CHECK (status IN ('PENDING', 'RESOLVED')),
    CONSTRAINT uq_quarantine_identity UNIQUE (device_id, user_code, punched_at_local, type)
);

CREATE INDEX ix_quarantine_pending ON quarantined_punch (received_at) WHERE status = 'PENDING';
