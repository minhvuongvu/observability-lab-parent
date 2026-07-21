-- ---------------------------------------------------------------------------
-- V3 - Carry the correlation context through the outbox.
--
-- The outbox decoupled producing an event from delivering it, and in doing so
-- broke the correlation chain: the row is written inside the request that
-- caused it, but the relay publishes later, on a scheduler thread that has no
-- MDC and no way to recover one. The Inventory Service therefore received no
-- correlation header and fell back to the order number, so a single business
-- transaction appeared under two different identifiers either side of the
-- broker - which defeats the point of having a correlation id at all.
--
-- The fix is to treat the correlation context as part of the event's durable
-- state rather than as ambient thread state. It is known at write time; storing
-- it means the relay can restore it onto the outbound record minutes later.
-- ---------------------------------------------------------------------------

-- Nullable: an event may legitimately originate outside a request (a scheduled
-- job, a replay), and rows written by V2 predate the column.
ALTER TABLE outbox_events ADD COLUMN correlation_id VARCHAR(128);

-- Stored alongside it so a trace survives the same hop once step 12 mints
-- these. Recording it now costs nothing and avoids a second migration.
ALTER TABLE outbox_events ADD COLUMN trace_id VARCHAR(64);

COMMENT ON COLUMN outbox_events.correlation_id IS
    'Correlation context captured when the event was produced, replayed onto the Kafka record by the relay.';
