package com.smarttoll;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:4200")
public class Api {
    private final JdbcTemplate db;
    private final GateEventStream gateEventStream;

    public Api(JdbcTemplate db, GateEventStream gateEventStream) {
        this.db = db;
        this.gateEventStream = gateEventStream;
    }

    private void publishGateStateAfterCommit(long laneId, String state) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Gate event publishing requires an active transaction");
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        gateEventStream.publish(laneId, state);
                    }
                });
    }

    public record VehicleRequest(String plateNumber, String category, BigDecimal initialBalance) {
    }

    public record PassageRequest(UUID eventId, String plateNumber, long laneId) {
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/vehicles")
    public List<Map<String, Object>> vehicles() {
        return db.queryForList(
                "SELECT v.id,v.plate_number,v.category,w.balance FROM vehicles v JOIN wallets w ON w.vehicle_id=v.id ORDER BY v.id");
    }

    @GetMapping("/transactions")
    public List<Map<String, Object>> transactions() {
        return db.queryForList("SELECT * FROM toll_transactions ORDER BY id DESC LIMIT 100");
    }

    @GetMapping("/lanes")
    public List<Map<String, Object>> lanes() {
        return db.queryForList("SELECT * FROM toll_lanes ORDER BY id");
    }

    @PostMapping("/vehicles")
    @Transactional
    public Map<String, Object> register(@RequestBody VehicleRequest r) {
        if (r.plateNumber() == null || !r.plateNumber().matches("[A-Za-z0-9-]{3,32}")
                || !List.of("CAR", "TRUCK", "BUS").contains(r.category()) || r.initialBalance() == null
                || r.initialBalance().signum() < 0 || r.initialBalance().scale() > 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid vehicle data");
        Long id = db.queryForObject("INSERT INTO vehicles(plate_number,category) VALUES (?,?) RETURNING id", Long.class,
                r.plateNumber().toUpperCase(Locale.ROOT), r.category());
        db.update("INSERT INTO wallets(vehicle_id,balance) VALUES (?,?)", id, r.initialBalance());
        return Map.of("vehicleId", id, "plateNumber", r.plateNumber().toUpperCase(Locale.ROOT));
    }

    @PostMapping("/passages")
    @Transactional
    public Map<String, Object> passage(@RequestBody PassageRequest r) {
        if (r.eventId() == null || r.plateNumber() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventId and plateNumber required");
        var old = db.queryForList("SELECT id,status,amount FROM toll_transactions WHERE event_id=?", r.eventId());
        if (!old.isEmpty())
            return Map.of("transaction", old.get(0), "replayed", true);

        String mode = db.queryForObject(
                "SELECT payment_mode FROM toll_lanes WHERE id = ?",
                String.class,
                r.laneId());

        if (!"WALLET".equals(mode)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Use the payment-specific endpoint for this lane");
        }

        var lane = db.queryForList("SELECT id,station_id,gate_state FROM toll_lanes WHERE id=? FOR UPDATE", r.laneId());
        if (lane.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lane not found");
        if (!"CLOSED".equals(lane.get(0).get("gate_state")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Lane is busy");

        var vehicle = db.queryForList(
                "SELECT v.id,v.category,w.id AS wallet_id,w.balance FROM vehicles v JOIN wallets w ON w.vehicle_id=v.id WHERE v.plate_number=? FOR UPDATE OF w",
                r.plateNumber().toUpperCase(Locale.ROOT));
        if (vehicle.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehicle not registered");
        var v = vehicle.get(0);
        BigDecimal fee = db.queryForObject("SELECT amount FROM toll_tariffs WHERE station_id=? AND vehicle_category=?",
                BigDecimal.class, lane.get(0).get("station_id"), v.get("category"));
        if (fee == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No tariff configured");
        boolean paid = ((BigDecimal) v.get("balance")).compareTo(fee) >= 0;
        String status = paid ? "AUTHORIZED" : "INSUFFICIENT_FUNDS";
        Long tx = db.queryForObject(
                "INSERT INTO toll_transactions(event_id,vehicle_id,lane_id,amount,status) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, r.eventId(), v.get("id"), r.laneId(), fee, status);
        if (paid) {
            db.update(
                    "UPDATE wallets SET balance=balance-? WHERE id=?",
                    fee, v.get("wallet_id"));

            db.update(
                    "INSERT INTO wallet_ledger(wallet_id,transaction_id,amount,type) " +
                            "VALUES (?,?,?,'TOLL_DEBIT')",
                    v.get("wallet_id"), tx, fee.negate());

            db.update(
                    "UPDATE toll_lanes SET gate_state='OPEN' WHERE id=?",
                    r.laneId());

            db.update(
                    "INSERT INTO gate_events(lane_id,transaction_id,event_type) " +
                            "VALUES (?,?,'OPEN')",
                    r.laneId(), tx);

            publishGateStateAfterCommit(r.laneId(), "OPEN");
        }
        return Map.of("transactionId", tx, "status", status, "fee", fee, "gateState", paid ? "OPEN" : "CLOSED",
                "replayed", false);
    }

    @PostMapping("/lanes/{id}/complete")
    @Transactional
    public Map<String, String> complete(@PathVariable long id) {

        var lane = db.queryForList(
                "SELECT gate_state FROM toll_lanes WHERE id=? FOR UPDATE",
                id);

        if (lane.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Lane not found");
        }

        if (!"OPEN".equals(lane.get(0).get("gate_state"))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Gate not open");
        }

        db.update(
                "UPDATE toll_lanes SET gate_state='CLOSED' WHERE id=?",
                id);

        db.update(
                "INSERT INTO gate_events(lane_id,event_type) " +
                        "VALUES (?,'PASSAGE_COMPLETE')",
                id);

        publishGateStateAfterCommit(id, "CLOSED");

        return Map.of("gateState", "CLOSED");
    }
}
