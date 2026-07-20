-- ---------------------------------------------------------------------------
-- V1 - Order Service schema.
--
-- Flyway owns the schema; Hibernate is configured with ddl-auto=validate and
-- may never alter it. The service refuses to start if the two disagree, which
-- turns schema drift into a startup failure instead of a runtime surprise.
-- ---------------------------------------------------------------------------

-- Allocation size 50 must stay identical to the @SequenceGenerator in the
-- entities. If they diverge, Hibernate hands out identifiers the database has
-- already issued and inserts fail on the unique constraint.
CREATE SEQUENCE order_id_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE order_item_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE orders (
    id            BIGINT         PRIMARY KEY,
    -- Business key. Callers address orders by this, never by the surrogate id,
    -- so the internal identifier stays free to change.
    order_number  VARCHAR(40)    NOT NULL,
    customer_id   VARCHAR(64)    NOT NULL,
    status        VARCHAR(20)    NOT NULL,
    total_amount  NUMERIC(19, 2) NOT NULL,
    -- VARCHAR rather than CHAR: CHAR pads to its declared width, so a value
    -- read back compares unequal to the one that was written.
    currency      VARCHAR(3)     NOT NULL,
    -- Optimistic locking. Two concurrent updates would otherwise silently lose one.
    version       BIGINT         NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL,
    created_by    VARCHAR(100),
    updated_at    TIMESTAMPTZ,
    updated_by    VARCHAR(100),

    CONSTRAINT uq_orders_order_number UNIQUE (order_number),
    CONSTRAINT ck_orders_total_amount CHECK (total_amount >= 0)
);

-- Supports "my orders", the most common query on this table.
CREATE INDEX idx_orders_customer_id ON orders (customer_id);
-- Supports filtering a backlog by state.
CREATE INDEX idx_orders_status ON orders (status);
-- Descending: every listing is newest-first, and a matching index order lets
-- PostgreSQL walk it directly instead of sorting the result set.
CREATE INDEX idx_orders_created_at ON orders (created_at DESC);

CREATE TABLE order_items (
    id          BIGINT         PRIMARY KEY,
    order_id    BIGINT         NOT NULL,
    product_sku VARCHAR(64)    NOT NULL,
    quantity    INTEGER        NOT NULL,
    unit_price  NUMERIC(19, 2) NOT NULL,
    version     BIGINT         NOT NULL,

    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id)
        REFERENCES orders (id) ON DELETE CASCADE,
    -- Enforced here as well as in the domain: the database is the last line of
    -- defence, and it is the only one a stray script cannot bypass.
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_items_unit_price CHECK (unit_price >= 0)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);

COMMENT ON TABLE orders IS 'Order aggregate root. Owned exclusively by the Order Service.';
COMMENT ON COLUMN orders.order_number IS 'Externally visible business key.';
COMMENT ON COLUMN orders.status IS 'PENDING, CONFIRMED, REJECTED or CANCELLED.';
