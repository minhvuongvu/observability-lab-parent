-- ---------------------------------------------------------------------------
-- V2 - Remember what was decided, not merely that a decision was made.
--
-- V1's processed_events answered "have I handled this event?", which is enough
-- to stop a redelivery reserving stock twice. It is not enough to re-announce
-- the result.
--
-- The Order Service learns the outcome from inventory-updated. If that publish
-- fails, the listener throws and Kafka redelivers order-created - but by then
-- the reservation is committed and the event is marked handled, so the retry
-- would find "already processed" and have nothing to announce. The order would
-- stay PENDING forever, with the stock reserved.
--
-- Storing the decision closes that hole: a redelivery replays the same
-- settlement instead of discarding it, which is what makes the publish safely
-- retryable and the whole flow eventually consistent rather than merely
-- usually consistent.
-- ---------------------------------------------------------------------------

-- Nullable: rows written by V1 predate the column and there is no decision to
-- back-fill for them. A NULL simply means "handled before outcomes were
-- recorded", and the listener treats it as nothing left to announce.
ALTER TABLE processed_events ADD outcome VARCHAR2(20 CHAR);

-- The shortages that justified a REJECTED decision, rendered as the same text
-- the event carries. Kept so a replayed settlement tells the Order Service
-- exactly what it was told the first time.
ALTER TABLE processed_events ADD outcome_detail VARCHAR2(1000 CHAR);

COMMENT ON COLUMN processed_events.outcome IS
    'RESERVED or REJECTED - the decision to replay if the settlement must be re-announced.';
