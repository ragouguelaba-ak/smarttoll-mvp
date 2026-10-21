-- Test-only RFID vehicle
INSERT INTO vehicles (plate_number, category, rfid_tag)
VALUES ('XYZ-456', 'CAR', 'RFID-XYZ-456')
ON CONFLICT (plate_number) DO NOTHING;

-- Test-only simulated payment card
INSERT INTO vehicle_card_tokens
    (vehicle_id, provider, payment_token, card_last4, active)
SELECT
    id,
    'SIMULATED',
    'tok_sim_visa_4242',
    '4242',
    TRUE
FROM vehicles
WHERE plate_number = 'XYZ-456'
ON CONFLICT (vehicle_id) DO NOTHING;

-- Ensure test lanes begin closed
UPDATE toll_lanes
SET gate_state = 'CLOSED'
WHERE lane_code IN ('L1', 'L2');

UPDATE vehicle_card_tokens
SET payment_token = 'tok_sim_visa_4242',
    active = TRUE
WHERE vehicle_id = (
    SELECT id FROM vehicles
    WHERE plate_number = 'XYZ-456'
);
