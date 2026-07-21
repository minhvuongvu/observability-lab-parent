-- ---------------------------------------------------------------------------
-- V4 - Remember which span produced the event, not just which trace.
--
-- V3 stored trace_id so a relayed event could be tied back to the business
-- transaction that caused it. That is enough to correlate logs; it is not
-- enough to *link* spans, because a link identifies one specific span, not a
-- whole trace.
--
-- The relay publishes minutes after the request that produced the row, on a
-- scheduler thread. Parenting the resulting Kafka span to the original request
-- would produce a trace with a minutes-long gap in the middle and a parent that
-- finished long before its child started. A span link records the same causal
-- relationship without distorting the timeline, and a link needs both ids.
-- ---------------------------------------------------------------------------

-- Nullable for the same reasons as V3's columns: an event may originate outside
-- a traced request, and rows written before this migration have no span to name.
ALTER TABLE outbox_events ADD COLUMN span_id VARCHAR(32);

COMMENT ON COLUMN outbox_events.span_id IS
    'Span that produced this event. Paired with trace_id to build a span link when it is relayed.';
