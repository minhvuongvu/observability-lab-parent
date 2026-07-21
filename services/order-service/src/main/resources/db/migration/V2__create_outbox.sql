-- ---------------------------------------------------------------------------
-- V2 - Transactional outbox.
--
-- Closes the dual-write gap left by V1's flow. Writing the order to PostgreSQL
-- and sending to Kafka are two systems with no shared transaction: commit the
-- order, fail the send, and stock is never reserved for an order that exists.
-- Retrying the send in-process does not fix it either, because the process can
-- die between the two.
--
-- The outbox makes the event part of the same transaction as the order. Either
-- both are durable or neither is. A relay then moves rows to Kafka afterwards
-- and marks them published, which converts an atomicity problem the
-- application cannot solve into a delivery problem it can simply retry.
-- ---------------------------------------------------------------------------

CREATE SEQUENCE outbox_event_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE outbox_events (
    id           BIGINT       PRIMARY KEY,
    -- The event's own identity, carried in the payload and used by consumers to
    -- recognise a redelivery. Unique so a retry of the *write* cannot enqueue
    -- the same event twice.
    event_id     VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    -- Destination is stored per row rather than inferred from the type, so the
    -- relay stays a dumb pipe and never needs to know what an order is.
    topic        VARCHAR(120) NOT NULL,
    -- Kafka partition key. The order number, so every event for one order is
    -- ordered relative to the others.
    message_key  VARCHAR(64)  NOT NULL,
    payload      TEXT         NOT NULL,
    -- When the fact happened, not when it was sent. The two differ by however
    -- long the relay took, and consumers care about the former.
    occurred_at  TIMESTAMPTZ  NOT NULL,
    -- NULL means still owed to Kafka. This is the entire queue state.
    published_at TIMESTAMPTZ,
    -- Kept for observability: a row with a high attempt count and a last_error
    -- is the visible form of a broker problem.
    attempts     INTEGER      NOT NULL DEFAULT 0,
    last_error   TEXT,
    version      BIGINT       NOT NULL,

    CONSTRAINT uq_outbox_events_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_events_attempts CHECK (attempts >= 0)
);

-- Partial index: the relay only ever asks for unpublished rows, and restricting
-- the index to them keeps it proportional to the backlog rather than to the
-- history. Published rows stay queryable for auditing without slowing the poll.
CREATE INDEX idx_outbox_events_unpublished
    ON outbox_events (occurred_at)
    WHERE published_at IS NULL;

COMMENT ON TABLE outbox_events IS
    'Events written atomically with the state change that produced them, relayed to Kafka afterwards.';
COMMENT ON COLUMN outbox_events.published_at IS
    'NULL until the relay has an acknowledged send. Non-NULL rows are history.';
