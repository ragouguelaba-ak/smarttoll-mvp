CREATE TABLE passage_request_identity (
    event_id UUID PRIMARY KEY,
    transaction_id BIGINT NOT NULL UNIQUE
        REFERENCES toll_transactions(id),
    payment_mode VARCHAR(20) NOT NULL
        CHECK (payment_mode IN ('CASH', 'RFID_CARD')),
    lane_id BIGINT NOT NULL
        REFERENCES toll_lanes(id),
    credential VARCHAR(100) NOT NULL,
    observed_plate VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_passage_request_identity_transaction
    ON passage_request_identity(transaction_id);
