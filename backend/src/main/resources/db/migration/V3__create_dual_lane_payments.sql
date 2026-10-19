-- SmartToll Phase 6.2
-- L1: Cash payments
-- L2: RFID-linked simulated card payments

-- 1. Add payment mode to lanes
ALTER TABLE toll_lanes
ADD COLUMN payment_mode VARCHAR(20) NOT NULL DEFAULT 'WALLET';

ALTER TABLE toll_lanes
ADD CONSTRAINT chk_lane_payment_mode
CHECK (payment_mode IN ('CASH', 'RFID_CARD', 'WALLET'));

-- 2. Configure L1 as CASH
UPDATE toll_lanes
SET payment_mode = 'CASH'
WHERE station_id = 1
  AND lane_code = 'L1';

-- 3. Create L2 for RFID card payments
-- Synchronize the lane ID sequence with existing records.
SELECT setval(
    pg_get_serial_sequence('toll_lanes', 'id'),
    COALESCE((SELECT MAX(id) FROM toll_lanes), 1),
    EXISTS (SELECT 1 FROM toll_lanes)
);

INSERT INTO toll_lanes (
    station_id,
    lane_code,
    gate_state,
    payment_mode
)
VALUES (
    1,
    'L2',
    'CLOSED',
    'RFID_CARD'
)
ON CONFLICT (station_id, lane_code)
DO UPDATE SET payment_mode = EXCLUDED.payment_mode;

-- 4. Store simulated payment credentials
CREATE TABLE vehicle_card_tokens (
    id BIGSERIAL PRIMARY KEY,

    vehicle_id BIGINT NOT NULL UNIQUE
        REFERENCES vehicles(id),

    provider VARCHAR(40) NOT NULL DEFAULT 'SIMULATED',

    payment_token VARCHAR(120) NOT NULL UNIQUE,

    card_last4 CHAR(4) NOT NULL,

    active BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 5. Record cash and simulated card payments
CREATE TABLE toll_payments (
    id BIGSERIAL PRIMARY KEY,

    transaction_id BIGINT NOT NULL UNIQUE
        REFERENCES toll_transactions(id),

    payment_method VARCHAR(20) NOT NULL,

    amount NUMERIC(14,2) NOT NULL
        CHECK (amount >= 0),

    status VARCHAR(30) NOT NULL,

    provider_reference VARCHAR(120),

    cash_received NUMERIC(14,2),

    change_due NUMERIC(14,2),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_payment_method
        CHECK (payment_method IN ('CASH', 'RFID_CARD', 'WALLET')),

    CONSTRAINT chk_cash_amounts
        CHECK (
            payment_method <> 'CASH'
            OR (
                cash_received IS NOT NULL
                AND change_due IS NOT NULL
                AND cash_received >= amount
                AND change_due = cash_received - amount
            )
        )
);

-- 6. Indexes
CREATE INDEX idx_toll_payments_method
ON toll_payments(payment_method, created_at DESC);

CREATE INDEX idx_gate_events_lane
ON gate_events(lane_id, created_at DESC);
