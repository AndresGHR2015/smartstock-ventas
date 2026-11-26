CREATE TABLE sales (
    id_hash bytea PRIMARY KEY,
    sale_id bytea NOT NULL,
    sku bytea NOT NULL,
    quantity integer NOT NULL CHECK (quantity > 0),
    status text NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED')),
    rejection_reason text,
    last_attempt timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'REJECTED' AND rejection_reason IS NOT NULL AND rejection_reason IN ('PRODUCT_NOT_FOUND', 'INSUFFICIENT_STOCK'))
        OR (status <> 'REJECTED' AND rejection_reason IS NULL))
);
CREATE INDEX sales_pending_attempt_idx ON sales (last_attempt NULLS FIRST, created_at) WHERE status = 'PENDING';
CREATE INDEX sales_sku_idx ON sales (md5(sku), created_at);
