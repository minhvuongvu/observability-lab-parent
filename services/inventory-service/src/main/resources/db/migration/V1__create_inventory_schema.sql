-- ---------------------------------------------------------------------------
-- V1 - Inventory Service schema (Oracle).
--
-- Flyway owns the schema; Hibernate runs with ddl-auto=validate and may never
-- alter it. The service refuses to start if the two disagree.
--
-- Lengths are declared CHAR rather than the Oracle default of BYTE. With byte
-- semantics a 64-"character" column silently holds fewer characters as soon as
-- the data is not ASCII, and the failure appears as a truncation error on a
-- value that looks well within the limit.
-- ---------------------------------------------------------------------------

-- INCREMENT BY 50 must stay identical to the @SequenceGenerator allocationSize
-- in the entities, or Hibernate hands out identifiers Oracle has already issued.
CREATE SEQUENCE stock_level_id_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE stock_movement_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE stock_levels (
    id                 NUMBER(19)                     NOT NULL,
    product_sku        VARCHAR2(64 CHAR)              NOT NULL,
    -- Units that can still be promised to a new order.
    available_quantity NUMBER(10)                     NOT NULL,
    -- Units already committed to accepted orders but not yet shipped.
    reserved_quantity  NUMBER(10)                     NOT NULL,
    version            NUMBER(19)                     NOT NULL,
    created_at         TIMESTAMP(6) WITH TIME ZONE    NOT NULL,
    created_by         VARCHAR2(100 CHAR),
    updated_at         TIMESTAMP(6) WITH TIME ZONE,
    updated_by         VARCHAR2(100 CHAR),

    CONSTRAINT pk_stock_levels PRIMARY KEY (id),
    CONSTRAINT uq_stock_levels_sku UNIQUE (product_sku),
    -- The database is the last line of defence for these invariants, and the
    -- only one a stray script cannot bypass.
    CONSTRAINT ck_stock_available_positive CHECK (available_quantity >= 0),
    CONSTRAINT ck_stock_reserved_positive CHECK (reserved_quantity >= 0)
);

CREATE TABLE stock_movements (
    id             NUMBER(19)                  NOT NULL,
    stock_level_id NUMBER(19)                  NOT NULL,
    -- RECEIPT, RESERVATION, RELEASE or ADJUSTMENT.
    movement_type  VARCHAR2(20 CHAR)           NOT NULL,
    quantity       NUMBER(10)                  NOT NULL,
    -- What caused the movement, normally an order number. This is what turns
    -- "stock is wrong" into an answerable question.
    reference      VARCHAR2(64 CHAR),
    occurred_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version        NUMBER(19)                  NOT NULL,

    CONSTRAINT pk_stock_movements PRIMARY KEY (id),
    CONSTRAINT fk_stock_movements_level FOREIGN KEY (stock_level_id)
        REFERENCES stock_levels (id) ON DELETE CASCADE,
    CONSTRAINT ck_stock_movements_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_stock_movements_level ON stock_movements (stock_level_id);
CREATE INDEX idx_stock_movements_reference ON stock_movements (reference);

-- ---------------------------------------------------------------------------
-- Consumer de-duplication.
--
-- Kafka delivers at least once, so a redelivery after a commit that the broker
-- did not observe is normal operation rather than an error. Recording the event
-- id in the same transaction as the stock change is what makes reprocessing
-- harmless: the second attempt collides on the primary key and stops.
-- ---------------------------------------------------------------------------
CREATE TABLE processed_events (
    event_id     VARCHAR2(64 CHAR)           NOT NULL,
    event_type   VARCHAR2(64 CHAR)           NOT NULL,
    processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_processed_events PRIMARY KEY (event_id)
);

-- Supports pruning entries older than the topic's retention, which is the point
-- at which a redelivery is no longer possible.
CREATE INDEX idx_processed_events_at ON processed_events (processed_at);

COMMENT ON TABLE stock_levels IS 'Stock aggregate root. Owned exclusively by the Inventory Service.';
COMMENT ON TABLE stock_movements IS 'Append-only audit trail of every change to a stock level.';
COMMENT ON TABLE processed_events IS 'Event ids already applied, for idempotent consumption.';
