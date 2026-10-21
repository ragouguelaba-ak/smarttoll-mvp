package com.smarttoll;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@Service
public class VehicleCrossingService {

    private final JdbcTemplate db;
    private final GateEventStream events;

    public VehicleCrossingService(
            JdbcTemplate db,
            GateEventStream events) {
        this.db = db;
        this.events = events;
    }

    @Transactional
    public Map<String, Object> complete(
            UUID eventId,
            long transactionId,
            long laneId) {

        if (eventId == null || transactionId <= 0 || (laneId != 1 && laneId != 2)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid crossing event");
        }

        // Serialize all gate operations for this lane.
        var lanes = db.queryForList(
                """
                        SELECT gate_state, payment_mode
                        FROM toll_lanes
                        WHERE id = ?
                        FOR UPDATE
                        """,
                laneId);

        if (lanes.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Lane not found");
        }

        String expectedMode = laneId == 1
                ? "CASH"
                : "RFID_CARD";

        if (!expectedMode.equals(lanes.get(0).get("payment_mode"))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Unexpected lane payment mode");
        }

        // Check that the event identifies the correct transaction.
        var transactions = db.queryForList(
                """
                        SELECT id, status
                        FROM toll_transactions
                        WHERE id = ?
                          AND event_id = ?
                          AND lane_id = ?
                        """,
                transactionId,
                eventId,
                laneId);

        if (transactions.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Crossing event does not match transaction");
        }

        if (!"AUTHORIZED".equals(
                transactions.get(0).get("status"))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Transaction not authorized");
        }

        var payments = db.queryForList(
                """
                        SELECT id
                        FROM toll_payments
                        WHERE transaction_id = ?
                          AND payment_method = ?
                          AND status IN ('PAID', 'APPROVED')
                        """,
                transactionId,
                expectedMode);

        if (payments.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "No successful payment for crossing");
        }

        // Idempotent handling of duplicate crossing messages.
        Integer completed = db.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM gate_events
                        WHERE transaction_id = ?
                          AND event_type = 'PASSAGE_COMPLETE'
                        """,
                Integer.class,
                transactionId);

        if (completed != null && completed > 0) {
            return Map.of(
                    "transactionId", transactionId,
                    "gateState", "CLOSED",
                    "replayed", true);
        }

        if (!"OPEN".equals(lanes.get(0).get("gate_state"))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Gate is not open");
        }

        // The latest OPEN must belong to this transaction.
        var activeOpen = db.queryForList(
                """
                        SELECT transaction_id
                        FROM gate_events
                        WHERE lane_id = ?
                          AND event_type = 'OPEN'
                        ORDER BY id DESC
                        LIMIT 1
                        """,
                laneId);

        if (activeOpen.isEmpty()
                || activeOpen.get(0).get("transaction_id") == null
                || ((Number) activeOpen.get(0)
                        .get("transaction_id")).longValue() != transactionId) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Transaction is not the active passage");
        }

        db.update(
                """
                        UPDATE toll_lanes
                        SET gate_state = 'CLOSED'
                        WHERE id = ?
                        """,
                laneId);

        db.update(
                """
                        INSERT INTO gate_events
                            (lane_id, transaction_id, event_type)
                        VALUES (?, ?, 'PASSAGE_COMPLETE')
                        """,
                laneId,
                transactionId);

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            events.publish(laneId, "CLOSED");
                        } catch (Exception ex) {
                            System.err.println(
                                    "SSE publication failed: "
                                            + ex.getMessage());
                        }
                    }
                });

        return Map.of(
                "transactionId", transactionId,
                "gateState", "CLOSED",
                "replayed", false);
    }
}