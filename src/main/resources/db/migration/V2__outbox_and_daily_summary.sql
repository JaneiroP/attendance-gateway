-- Events waiting to be published. A row is written in the same transaction as
-- the punch it describes, so an event exists if and only if the punch does.
CREATE TABLE outbox_event (
    -- Publication order. An identity column, not the event id, so the
    -- publisher can read the oldest events first.
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      UUID         NOT NULL UNIQUE,
    topic         VARCHAR(120) NOT NULL,
    message_key   VARCHAR(120) NOT NULL,
    payload       TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    published_at  TIMESTAMPTZ
);

CREATE INDEX ix_outbox_unpublished ON outbox_event (id) WHERE published_at IS NULL;

-- The daily summary consumer's own copy of the punches it has seen. Its
-- primary key is what makes the consumer idempotent: an event delivered twice
-- inserts nothing the second time.
CREATE TABLE summary_punch (
    event_id        UUID         PRIMARY KEY,
    employee_id     VARCHAR(64)  NOT NULL,
    work_date       DATE         NOT NULL,
    punched_at_utc  TIMESTAMPTZ  NOT NULL,
    type            VARCHAR(8)   NOT NULL,
    CONSTRAINT ck_summary_punch_type CHECK (type IN ('IN', 'OUT'))
);

CREATE INDEX ix_summary_punch_day ON summary_punch (employee_id, work_date);

CREATE TABLE daily_summary (
    employee_id     VARCHAR(64)  NOT NULL,
    work_date       DATE         NOT NULL,
    first_in        TIMESTAMPTZ,
    last_out        TIMESTAMPTZ,
    worked_minutes  INTEGER      NOT NULL,
    punch_count     INTEGER      NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (employee_id, work_date)
);
