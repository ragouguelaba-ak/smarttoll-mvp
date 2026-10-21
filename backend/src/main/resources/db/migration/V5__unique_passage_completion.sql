-- Ensure each transaction can have only one completed passage.
-- Other gate event types remain unrestricted.

CREATE UNIQUE INDEX uq_gate_events_passage_complete
ON gate_events (transaction_id)
WHERE event_type = 'PASSAGE_COMPLETE'
  AND transaction_id IS NOT NULL;