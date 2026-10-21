
package com.smarttoll;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:4200")
public class DualLanePaymentController {

        private final JdbcTemplate db;
        private final SimulatedCardGateway gateway;
        private final GateEventStream events;
        private final GateCommandPublisher gateCommands;

        public DualLanePaymentController(
                        JdbcTemplate db,
                        SimulatedCardGateway gateway,
                        GateEventStream events,
                        GateCommandPublisher gateCommands) {
                this.db = db;
                this.gateway = gateway;
                this.events = events;
                this.gateCommands = gateCommands;
        }

        public record CashRequest(
                        UUID eventId,
                        String plateNumber,
                        long laneId) {
        }

        public record CashConfirmation(
                        BigDecimal cashReceived) {
        }

        public record LinkCardRequest(
                        String rfidTag,
                        String paymentToken,
                        String cardLast4) {
        }

        public record CardPassageRequest(
                        UUID eventId,
                        String rfidTag,
                        String observedPlate,
                        long laneId) {
        }

        private Map<String, Object> checkReplay(
                        UUID eventId,
                        String paymentMode,
                        long laneId,
                        String credential,
                        String observedPlate) {
                // Serialize requests sharing the same event ID.
                db.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                        try (var statement = connection.prepareStatement(
                                        "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))")) {
                                statement.setString(1, eventId.toString());
                                statement.execute();
                        }
                        return null;
                });

                var previous = db.queryForList(
                                """
                                                SELECT t.id, t.status, t.amount,
                                                       p.payment_mode, p.lane_id,
                                                       p.credential, p.observed_plate
                                                FROM toll_transactions t
                                                LEFT JOIN passage_request_identity p
                                                  ON p.transaction_id = t.id
                                                WHERE t.event_id = ?
                                                """,
                                eventId);

                if (previous.isEmpty()) {
                        return null;
                }

                var existing = previous.get(0);

                if (existing.get("payment_mode") == null) {
                        throw new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "Legacy event cannot be safely replayed");
                }

                boolean matches = paymentMode.equals(existing.get("payment_mode")) &&
                                ((Number) existing.get("lane_id")).longValue() == laneId &&
                                credential.equals(existing.get("credential")) &&
                                java.util.Objects.equals(
                                                observedPlate,
                                                existing.get("observed_plate"));

                if (!matches) {
                        throw new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "Event ID reused with different request details");
                }

                return Map.of(
                                "transactionId", existing.get("id"),
                                "status", existing.get("status"),
                                "paymentMethod", existing.get("payment_mode"),
                                "fee", existing.get("amount"),
                                "replayed", true);
        }

        private void saveRequestIdentity(
                        UUID eventId,
                        long transactionId,
                        String paymentMode,
                        long laneId,
                        String credential,
                        String observedPlate) {
                db.update(
                                """
                                                INSERT INTO passage_request_identity
                                                  (event_id, transaction_id, payment_mode,
                                                   lane_id, credential, observed_plate)
                                                VALUES (?, ?, ?, ?, ?, ?)
                                                """,
                                eventId,
                                transactionId,
                                paymentMode,
                                laneId,
                                credential,
                                observedPlate);
        }

        private ResponseStatusException bad(String message) {
                return new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, message);
        }

        private Map<String, Object> requireLane(
                        long laneId, String expectedMode) {
                var lanes = db.queryForList(
                                """
                                                SELECT id, station_id, gate_state, payment_mode
                                                FROM toll_lanes WHERE id = ? FOR UPDATE
                                                """,
                                laneId);

                if (lanes.isEmpty()) {
                        throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Lane not found");
                }

                var lane = lanes.get(0);

                if (!expectedMode.equals(lane.get("payment_mode"))) {
                        throw bad("Wrong payment lane");
                }

                if (!"CLOSED".equals(lane.get("gate_state"))) {
                        throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, "Lane is busy");
                }

                return lane;
        }

        private Map<String, Object> requireVehicle(String plate) {
                if (plate == null || plate.isBlank()) {
                        throw bad("Vehicle plate required");
                }

                var vehicles = db.queryForList(
                                """
                                                SELECT id, plate_number, category
                                                FROM vehicles
                                                WHERE plate_number = ?
                                                """,
                                plate.toUpperCase(Locale.ROOT));

                if (vehicles.isEmpty()) {
                        throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Vehicle not registered");
                }

                return vehicles.get(0);
        }

        private BigDecimal tariff(
                        Object stationId, Object category) {
                var fees = db.queryForList(
                                """
                                                SELECT amount FROM toll_tariffs
                                                WHERE station_id = ?
                                                  AND vehicle_category = ?
                                                """,
                                stationId, category);

                if (fees.isEmpty()) {
                        throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, "Tariff not configured");
                }

                return (BigDecimal) fees.get(0).get("amount");
        }

        private void publishAfterCommit(long laneId, String state) {
                TransactionSynchronizationManager.registerSynchronization(
                                new TransactionSynchronization() {
                                        @Override
                                        public void afterCommit() {
                                                try {
                                                        events.publish(laneId, state);
                                                } catch (Exception ex) {
                                                        System.err.println(
                                                                        "SSE publication failed: " + ex.getMessage());
                                                }
                                        }
                                });
        }

        // L1: create a cash payment awaiting collection.
        @PostMapping("/cash/passages")
        @Transactional
        public Map<String, Object> createCash(@RequestBody CashRequest request) {
                if (request.eventId() == null) {
                        throw bad("eventId required");
                }

                if (request.plateNumber() == null ||
                                request.plateNumber().isBlank()) {
                        throw bad("Vehicle plate required");
                }

                String normalizedPlate = request.plateNumber().toUpperCase(Locale.ROOT);

                var replay = checkReplay(
                                request.eventId(),
                                "CASH",
                                request.laneId(),
                                normalizedPlate,
                                null);

                if (replay != null) {
                        return replay;
                }

                var lane = requireLane(request.laneId(), "CASH");
                var vehicle = requireVehicle(request.plateNumber());
                var fee = tariff(lane.get("station_id"), vehicle.get("category"));

                Long txId = db.queryForObject(
                                """
                                                INSERT INTO toll_transactions
                                                  (event_id, vehicle_id, lane_id, amount, status)
                                                VALUES (?, ?, ?, ?, 'PENDING_CASH')
                                                RETURNING id
                                                """,
                                Long.class,
                                request.eventId(),
                                vehicle.get("id"),
                                request.laneId(),
                                fee);

                saveRequestIdentity(
                                request.eventId(),
                                txId,
                                "CASH",
                                request.laneId(),
                                normalizedPlate,
                                null);

                return Map.of(
                                "transactionId", txId,
                                "status", "PENDING_CASH",
                                "fee", fee,
                                "gateState", "CLOSED",
                                "replayed", false);
        }

        // L1: cashier confirms collected cash.
        @PostMapping("/cash/passages/{id}/confirm")
        @Transactional
        public Map<String, Object> confirmCash(
                        @PathVariable long id,
                        @RequestBody CashConfirmation request) {
                var transactions = db.queryForList(
                                """
                                                SELECT id, lane_id, amount, status
                                                FROM toll_transactions
                                                WHERE id = ? FOR UPDATE
                                                """,
                                id);

                if (transactions.isEmpty()) {
                        throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Transaction not found");
                }

                var tx = transactions.get(0);

                if (!"PENDING_CASH".equals(tx.get("status"))) {
                        throw new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "Cash transaction already processed");
                }

                long laneId = ((Number) tx.get("lane_id")).longValue();
                requireLane(laneId, "CASH");

                BigDecimal fee = (BigDecimal) tx.get("amount");
                BigDecimal received = request.cashReceived();

                if (received == null ||
                                received.scale() > 2 ||
                                received.compareTo(fee) < 0) {
                        throw bad("Cash received must cover the toll");
                }

                BigDecimal change = received.subtract(fee);

                db.update(
                                """
                                                INSERT INTO toll_payments
                                                  (transaction_id, payment_method, amount,
                                                   status, cash_received, change_due)
                                                VALUES (?, 'CASH', ?, 'PAID', ?, ?)
                                                """,
                                id, fee, received, change);

                db.update(
                                "UPDATE toll_transactions SET status = 'AUTHORIZED' WHERE id = ?",
                                id);

                db.update(
                                "UPDATE toll_lanes SET gate_state = 'OPEN' WHERE id = ?",
                                laneId);

                db.update(
                                """
                                                INSERT INTO gate_events
                                                  (lane_id, transaction_id, event_type)
                                                VALUES (?, ?, 'OPEN')
                                                """,
                                laneId, id);

                publishAfterCommit(laneId, "OPEN");

                UUID eventId = db.queryForObject(
                                "SELECT event_id FROM toll_transactions WHERE id = ?",
                                UUID.class,
                                id);

                gateCommands.publishAfterCommit(
                                laneId,
                                eventId,
                                id,
                                "OPEN");

                return Map.of(
                                "transactionId", id,
                                "status", "AUTHORIZED",
                                "paymentMethod", "CASH",
                                "fee", fee,
                                "change", change,
                                "gateState", "OPEN");
        }

        // L2: register simulated RFID + card association.
        @PostMapping("/card/vehicles/{id}/link")
        @Transactional
        public Map<String, Object> linkCard(
                        @PathVariable long id,
                        @RequestBody LinkCardRequest request) {
                if (request.rfidTag() == null ||
                                !request.rfidTag().matches("[A-Za-z0-9-]{4,64}") ||
                                request.paymentToken() == null ||
                                !request.paymentToken().matches("tok_sim_[A-Za-z0-9_-]{4,100}") ||
                                request.cardLast4() == null ||
                                !request.cardLast4().matches("[0-9]{4}")) {
                        throw bad("Invalid simulated RFID/card details");
                }

                int updated = db.update(
                                "UPDATE vehicles SET rfid_tag = ? WHERE id = ?",
                                request.rfidTag(), id);

                if (updated == 0) {
                        throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Vehicle not found");
                }

                db.update(
                                """
                                                INSERT INTO vehicle_card_tokens
                                                  (vehicle_id, payment_token, card_last4)
                                                VALUES (?, ?, ?)
                                                ON CONFLICT (vehicle_id)
                                                DO UPDATE SET
                                                  payment_token = EXCLUDED.payment_token,
                                                  card_last4 = EXCLUDED.card_last4,
                                                  active = TRUE
                                                """,
                                id,
                                request.paymentToken(),
                                request.cardLast4());

                return Map.of(
                                "vehicleId", id,
                                "rfidTag", request.rfidTag(),
                                "cardLast4", request.cardLast4(),
                                "linked", true);
        }

        // L2: RFID primary credential, ANPR supporting match.
        @PostMapping("/card/passages")
        @Transactional
        public Map<String, Object> cardPassage(
                        @RequestBody CardPassageRequest request) {

                if (request.eventId() == null ||
                                request.rfidTag() == null ||
                                request.rfidTag().isBlank() ||
                                request.observedPlate() == null ||
                                request.observedPlate().isBlank()) {
                        throw bad("eventId, rfidTag and observedPlate required");
                }

                String normalizedPlate = request.observedPlate().toUpperCase(Locale.ROOT);

                var replay = checkReplay(
                                request.eventId(),
                                "RFID_CARD",
                                request.laneId(),
                                request.rfidTag(),
                                normalizedPlate);

                if (replay != null) {
                        return replay;
                }

                var lane = requireLane(request.laneId(), "RFID_CARD");

                var vehicles = db.queryForList(
                                """
                                                SELECT v.id, v.plate_number, v.category,
                                                       c.payment_token, c.active
                                                FROM vehicles v
                                                JOIN vehicle_card_tokens c ON c.vehicle_id = v.id
                                                WHERE v.rfid_tag = ?
                                                """,
                                request.rfidTag());

                if (vehicles.isEmpty()) {
                        throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "RFID or linked card not found");
                }

                var vehicle = vehicles.get(0);

                if (!Boolean.TRUE.equals(vehicle.get("active"))) {
                        throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, "Card inactive");
                }

                if (!request.observedPlate()
                                .toUpperCase(Locale.ROOT)
                                .equals(vehicle.get("plate_number"))) {
                        throw new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "RFID and ANPR plate mismatch");
                }

                BigDecimal fee = tariff(
                                lane.get("station_id"),
                                vehicle.get("category"));

                var payment = gateway.charge(
                                (String) vehicle.get("payment_token"),
                                fee,
                                request.eventId());

                String status = payment.approved()
                                ? "AUTHORIZED"
                                : "PAYMENT_FAILED";

                Long txId = db.queryForObject(
                                """
                                                INSERT INTO toll_transactions
                                                  (event_id, vehicle_id, lane_id, amount, status)
                                                VALUES (?, ?, ?, ?, ?)
                                                RETURNING id
                                                """,
                                Long.class,
                                request.eventId(),
                                vehicle.get("id"),
                                request.laneId(),
                                fee,
                                status);

                saveRequestIdentity(
                                request.eventId(),
                                txId,
                                "RFID_CARD",
                                request.laneId(),
                                request.rfidTag(),
                                normalizedPlate);

                db.update(
                                """
                                                INSERT INTO toll_payments
                                                  (transaction_id, payment_method, amount,
                                                   status, provider_reference)
                                                VALUES (?, 'RFID_CARD', ?, ?, ?)
                                                """,
                                txId,
                                fee,
                                payment.approved() ? "PAID" : "DECLINED",
                                payment.reference());

                if (payment.approved()) {
                        db.update(
                                        "UPDATE toll_lanes SET gate_state = 'OPEN' WHERE id = ?",
                                        request.laneId());

                        db.update(
                                        """
                                                        INSERT INTO gate_events
                                                          (lane_id, transaction_id, event_type)
                                                        VALUES (?, ?, 'OPEN')
                                                        """,
                                        request.laneId(), txId);

                        publishAfterCommit(request.laneId(), "OPEN");

                        gateCommands.publishAfterCommit(
                                request.laneId(),
                                request.eventId(),
                                txId,
                                "OPEN");
                }

                return Map.of(
                                "transactionId", txId,
                                "status", status,
                                "paymentMethod", "RFID_CARD",
                                "fee", fee,
                                "gateState", payment.approved() ? "OPEN" : "CLOSED",
                                "reason", payment.reason(),
                                "replayed", false);
        }

        @GetMapping("/payments")
        public List<Map<String, Object>> payments() {
                return db.queryForList(
                                """
                                                SELECT p.*, t.lane_id, t.vehicle_id
                                                FROM toll_payments p
                                                JOIN toll_transactions t ON t.id = p.transaction_id
                                                ORDER BY p.id DESC
                                                LIMIT 100
                                                """);
        }
}
