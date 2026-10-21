package com.smarttoll;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;
import java.util.UUID;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.test.context.jdbc.Sql;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "smarttoll.mqtt.enabled=false")
@AutoConfigureMockMvc
@Sql(scripts = "/sql/test-fixtures.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ReplaySecurityIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("smarttoll_test")
            .withUsername("smarttoll")
            .withPassword("test_password");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    VehicleCrossingService crossingService;

    private long authorizeCard(UUID eventId) throws Exception {
        String request = mapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "rfidTag", "RFID-XYZ-456",
                "observedPlate", "XYZ-456",
                "laneId", 2));

        MvcResult result = mvc.perform(
                post("/api/card/passages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andReturn();

        return mapper.readTree(
                result.getResponse().getContentAsString()).path("transactionId").asLong();
    }

    private long createCashPassage(UUID eventId) throws Exception {
        String request = mapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "plateNumber", "XYZ-456",
                "laneId", 1));

        MvcResult result = mvc.perform(post("/api/cash/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CASH"))
                .andReturn();

        return mapper.readTree(
                result.getResponse().getContentAsString())
                .path("transactionId").asLong();
    }

    private void confirmCashPassage(long transactionId)
            throws Exception {
        mvc.perform(post("/api/cash/passages/" + transactionId + "/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                        Map.of("cashReceived", 500))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.gateState").value("OPEN"));
    }

    @Test
    void alteredReplayMustReturnConflict() throws Exception {
        UUID eventId = UUID.randomUUID();

        var original = Map.of(
                "eventId", eventId.toString(),
                "rfidTag", "RFID-XYZ-456",
                "observedPlate", "XYZ-456",
                "laneId", 2);

        mvc.perform(post("/api/card/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(original)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false));

        var altered = Map.of(
                "eventId", eventId.toString(),
                "rfidTag", "RFID-XYZ-456",
                "observedPlate", "WRONG-999",
                "laneId", 2);

        mvc.perform(post("/api/card/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(altered)))
                .andExpect(status().isConflict());
    }

    @Test
    void identicalCardReplayMustReturnExistingTransaction() throws Exception {
        UUID eventId = UUID.randomUUID();

        String request = mapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "rfidTag", "RFID-XYZ-456",
                "observedPlate", "XYZ-456",
                "laneId", 2));

        mvc.perform(post("/api/card/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false));

        mvc.perform(post("/api/card/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void concurrentIdenticalRequestsMustCreateOnlyOneTransaction()
            throws Exception {

        UUID eventId = UUID.randomUUID();

        String request = mapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "rfidTag", "RFID-XYZ-456",
                "observedPlate", "XYZ-456",
                "laneId", 2));

        int requestCount = 5;

        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<MvcResult>> futures = new ArrayList<>();

            for (int i = 0; i < requestCount; i++) {
                Callable<MvcResult> task = () -> {
                    ready.countDown();
                    start.await();

                    return mvc.perform(post("/api/card/passages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                            .andReturn();
                };

                futures.add(executor.submit(task));
            }

            assertTrue(
                    ready.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "Workers did not become ready");

            start.countDown();

            int created = 0;
            int replayed = 0;
            Long expectedTransactionId = null;

            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(
                        30,
                        java.util.concurrent.TimeUnit.SECONDS);

                assertEquals(
                        200,
                        result.getResponse().getStatus(),
                        result.getResponse().getContentAsString());

                var response = mapper.readTree(
                        result.getResponse().getContentAsString());

                System.out.println(
                        "CONCURRENT RESPONSE: " +
                                result.getResponse().getContentAsString());

                boolean isReplay = response.path("replayed").asBoolean();

                if (isReplay) {
                    replayed++;
                } else {
                    created++;
                }

                long transactionId = response
                        .path("transactionId")
                        .asLong();

                assertTrue(
                        transactionId > 0,
                        "Invalid transaction ID: " + response);

                assertEquals(
                        "RFID_CARD",
                        response.path("paymentMethod").asText());

                assertEquals(
                        "AUTHORIZED",
                        response.path("status").asText());

                if (expectedTransactionId == null) {
                    expectedTransactionId = transactionId;
                } else {
                    assertEquals(
                            expectedTransactionId.longValue(),
                            transactionId);
                }
            }

            assertEquals(1, created);
            assertEquals(4, replayed);

            Integer transactionCount = jdbc.queryForObject(
                    """
                            SELECT COUNT(*)
                            FROM toll_transactions
                            WHERE event_id = ?
                            """,
                    Integer.class,
                    eventId);

            Integer paymentCount = jdbc.queryForObject(
                    """
                            SELECT COUNT(*)
                            FROM toll_payments p
                            JOIN toll_transactions t
                              ON t.id = p.transaction_id
                            WHERE t.event_id = ?
                            """,
                    Integer.class,
                    eventId);

            Integer identityCount = jdbc.queryForObject(
                    """
                            SELECT COUNT(*)
                            FROM passage_request_identity
                            WHERE event_id = ?
                            """,
                    Integer.class,
                    eventId);

            assertEquals(1, transactionCount);
            assertEquals(1, paymentCount);
            assertEquals(1, identityCount);

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void unknownRfidMustReturnNotFound() throws Exception {
        String request = mapper.writeValueAsString(Map.of(
                "eventId", UUID.randomUUID().toString(),
                "rfidTag", "RFID-UNKNOWN-999",
                "observedPlate", "XYZ-456",
                "laneId", 2));

        mvc.perform(post("/api/card/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isNotFound());
    }

    @Test
    void mismatchedPlateMustReturnConflict() throws Exception {
        String request = mapper.writeValueAsString(Map.of(
                "eventId", UUID.randomUUID().toString(),
                "rfidTag", "RFID-XYZ-456",
                "observedPlate", "WRONG-999",
                "laneId", 2));

        mvc.perform(post("/api/card/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isConflict());
    }

    @Test
    void cardEventIdCannotBeReusedForCashPayment() throws Exception {
        UUID eventId = UUID.randomUUID();

        String cardRequest = mapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "rfidTag", "RFID-XYZ-456",
                "observedPlate", "XYZ-456",
                "laneId", 2));

        mvc.perform(post("/api/card/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(cardRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false));

        String cashRequest = mapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "plateNumber", "XYZ-456",
                "laneId", 1));

        mvc.perform(post("/api/cash/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(cashRequest))
                .andExpect(status().isConflict());
    }

    @Test
    void replayMustPreserveCorePaymentResponseFields() throws Exception {
        UUID eventId = UUID.randomUUID();

        String request = mapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "rfidTag", "RFID-XYZ-456",
                "observedPlate", "XYZ-456",
                "laneId", 2));

        var original = mvc.perform(post("/api/card/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();

        var replay = mvc.perform(post("/api/card/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.paymentMethod").value("RFID_CARD"))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andReturn();

        var originalJson = mapper.readTree(
                original.getResponse().getContentAsString());

        var replayJson = mapper.readTree(
                replay.getResponse().getContentAsString());

        assertEquals(
                originalJson.path("transactionId").asLong(),
                replayJson.path("transactionId").asLong());

        assertEquals(
                0,
                originalJson.path("fee").decimalValue()
                        .compareTo(replayJson.path("fee").decimalValue()));
    }

    @Test
    void cashPaymentMustBeConfirmedOnlyOnce() throws Exception {
        UUID eventId = UUID.randomUUID();

        String request = mapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "plateNumber", "XYZ-456",
                "laneId", 1));

        var created = mvc.perform(post("/api/cash/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CASH"))
                .andReturn();

        long transactionId = mapper.readTree(
                created.getResponse().getContentAsString()).path("transactionId").asLong();

        assertTrue(transactionId > 0);

        String confirmation = mapper.writeValueAsString(
                Map.of("cashReceived", 1000));

        mvc.perform(post("/api/cash/passages/" + transactionId + "/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.gateState").value("OPEN"))
                .andExpect(jsonPath("$.change").value(500));

        mvc.perform(post("/api/cash/passages/" + transactionId + "/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmation))
                .andExpect(status().isConflict());

        Integer paymentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toll_payments WHERE transaction_id = ?",
                Integer.class,
                transactionId);

        assertEquals(1, paymentCount);
    }

    @Test
    void insufficientCashMustNotAuthorizePayment() throws Exception {
        UUID eventId = UUID.randomUUID();

        String request = mapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "plateNumber", "XYZ-456",
                "laneId", 1));

        var created = mvc.perform(post("/api/cash/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CASH"))
                .andReturn();

        long transactionId = mapper.readTree(
                created.getResponse().getContentAsString()).path("transactionId").asLong();

        mvc.perform(post("/api/cash/passages/" + transactionId + "/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                        Map.of("cashReceived", 100))))
                .andExpect(status().isBadRequest());

        String status = jdbc.queryForObject(
                "SELECT status FROM toll_transactions WHERE id = ?",
                String.class,
                transactionId);

        String gateState = jdbc.queryForObject(
                "SELECT gate_state FROM toll_lanes WHERE id = 1",
                String.class);

        Integer paymentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toll_payments WHERE transaction_id = ?",
                Integer.class,
                transactionId);

        assertEquals("PENDING_CASH", status);
        assertEquals("CLOSED", gateState);
        assertEquals(0, paymentCount);
    }

    @Test
    void confirmedCashReplayMustNotCreateAnotherPayment() throws Exception {
        UUID eventId = UUID.randomUUID();

        String request = mapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "plateNumber", "XYZ-456",
                "laneId", 1));

        var created = mvc.perform(post("/api/cash/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CASH"))
                .andReturn();

        long transactionId = mapper.readTree(
                created.getResponse().getContentAsString()).path("transactionId").asLong();

        mvc.perform(post("/api/cash/passages/" + transactionId + "/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                        Map.of("cashReceived", 500))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));

        mvc.perform(post("/api/cash/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.transactionId").value(transactionId))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));

        Integer transactionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toll_transactions WHERE event_id = ?",
                Integer.class,
                eventId);

        Integer paymentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toll_payments WHERE transaction_id = ?",
                Integer.class,
                transactionId);

        assertEquals(1, transactionCount);
        assertEquals(1, paymentCount);
    }

    @Test
    void gateCompletionMustCloseLane() throws Exception {
        UUID eventId = UUID.randomUUID();
        long transactionId = authorizeCard(eventId);

        String completionRequest = mapper.writeValueAsString(
                Map.of(
                        "eventId", eventId.toString(),
                        "transactionId", transactionId));

        mvc.perform(post("/api/lanes/2/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(completionRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateState").value("CLOSED"))
                .andExpect(jsonPath("$.replayed").value(false));

        mvc.perform(post("/api/lanes/2/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(completionRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        String gateState = jdbc.queryForObject(
                "SELECT gate_state FROM toll_lanes WHERE id = 2",
                String.class);

        assertEquals("CLOSED", gateState);

        Integer completionEvents = jdbc.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM gate_events
                        WHERE transaction_id = ?
                          AND event_type = 'PASSAGE_COMPLETE'
                        """,
                Integer.class,
                transactionId);

        assertEquals(1, completionEvents);

        mvc.perform(post("/api/lanes/2/complete"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void declinedCardMustNotOpenGate() throws Exception {
        jdbc.update(
                """
                        UPDATE vehicle_card_tokens
                        SET payment_token = ?
                        WHERE vehicle_id = (
                            SELECT id FROM vehicles
                            WHERE plate_number = 'XYZ-456'
                        )
                        """,
                "tok_sim_decline_4242");

        UUID eventId = UUID.randomUUID();

        String request = mapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "rfidTag", "RFID-XYZ-456",
                "observedPlate", "XYZ-456",
                "laneId", 2));

        var result = mvc.perform(post("/api/card/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.reason").value("CARD_DECLINED"))
                .andExpect(jsonPath("$.gateState").value("CLOSED"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();

        long transactionId = mapper.readTree(
                result.getResponse().getContentAsString()).path("transactionId").asLong();

        assertTrue(transactionId > 0);

        String gateState = jdbc.queryForObject(
                "SELECT gate_state FROM toll_lanes WHERE id = 2",
                String.class);

        String paymentStatus = jdbc.queryForObject(
                """
                        SELECT status FROM toll_payments
                        WHERE transaction_id = ?
                        """,
                String.class,
                transactionId);

        Integer openEvents = jdbc.queryForObject(
                """
                        SELECT COUNT(*) FROM gate_events
                        WHERE transaction_id = ?
                          AND event_type = 'OPEN'
                        """,
                Integer.class,
                transactionId);

        assertEquals("CLOSED", gateState);
        assertEquals("DECLINED", paymentStatus);
        assertEquals(0, openEvents);
    }

    @Test
    void declinedCardReplayMustRemainDeclined() throws Exception {
        jdbc.update(
                """
                        UPDATE vehicle_card_tokens
                        SET payment_token = ?
                        WHERE vehicle_id = (
                            SELECT id FROM vehicles
                            WHERE plate_number = 'XYZ-456'
                        )
                        """,
                "tok_sim_decline_4242");

        UUID eventId = UUID.randomUUID();

        String request = mapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "rfidTag", "RFID-XYZ-456",
                "observedPlate", "XYZ-456",
                "laneId", 2));

        var original = mvc.perform(post("/api/card/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();

        long transactionId = mapper.readTree(
                original.getResponse().getContentAsString()).path("transactionId").asLong();

        mvc.perform(post("/api/card/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.transactionId").value(transactionId));

        Integer transactionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toll_transactions WHERE event_id = ?",
                Integer.class,
                eventId);

        Integer paymentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toll_payments WHERE transaction_id = ?",
                Integer.class,
                transactionId);

        String paymentStatus = jdbc.queryForObject(
                "SELECT status FROM toll_payments WHERE transaction_id = ?",
                String.class,
                transactionId);

        String gateState = jdbc.queryForObject(
                "SELECT gate_state FROM toll_lanes WHERE id = 2",
                String.class);

        assertEquals(1, transactionCount);
        assertEquals(1, paymentCount);
        assertEquals("DECLINED", paymentStatus);
        assertEquals("CLOSED", gateState);
    }

    @Test
    void declinedPaymentRecoveryMustRequireNewEventId() throws Exception {
        // Simulate a declined card.
        jdbc.update(
                """
                        UPDATE vehicle_card_tokens
                        SET payment_token = 'tok_sim_decline_4242'
                        WHERE vehicle_id = (
                            SELECT id FROM vehicles
                            WHERE plate_number = 'XYZ-456'
                        )
                        """);

        UUID originalEventId = UUID.randomUUID();

        String originalRequest = mapper.writeValueAsString(Map.of(
                "eventId", originalEventId.toString(),
                "rfidTag", "RFID-XYZ-456",
                "observedPlate", "XYZ-456",
                "laneId", 2));

        var declined = mvc.perform(post("/api/card/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(originalRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.gateState").value("CLOSED"))
                .andReturn();

        long declinedId = mapper.readTree(
                declined.getResponse().getContentAsString()).path("transactionId").asLong();

        // Replace declined card with a valid simulated token.
        jdbc.update(
                """
                        UPDATE vehicle_card_tokens
                        SET payment_token = 'tok_sim_visa_4242'
                        WHERE vehicle_id = (
                            SELECT id FROM vehicles
                            WHERE plate_number = 'XYZ-456'
                        )
                        """);

        // Reusing the old event ID must not authorize payment.
        mvc.perform(post("/api/card/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(originalRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.transactionId").value(declinedId));

        // A new event ID represents a new payment attempt.
        UUID recoveryEventId = UUID.randomUUID();

        String recoveryRequest = mapper.writeValueAsString(Map.of(
                "eventId", recoveryEventId.toString(),
                "rfidTag", "RFID-XYZ-456",
                "observedPlate", "XYZ-456",
                "laneId", 2));

        mvc.perform(post("/api/card/passages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(recoveryRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.gateState").value("OPEN"));

        Integer originalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toll_transactions WHERE event_id = ?",
                Integer.class,
                originalEventId);

        Integer recoveryCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toll_transactions WHERE event_id = ?",
                Integer.class,
                recoveryEventId);

        String originalStatus = jdbc.queryForObject(
                "SELECT status FROM toll_transactions WHERE id = ?",
                String.class,
                declinedId);

        assertEquals(1, originalCount);
        assertEquals(1, recoveryCount);
        assertEquals("PAYMENT_FAILED", originalStatus);
    }

    @Test
    void validCrossingMustCloseGate() throws Exception {
        UUID eventId = UUID.randomUUID();
        long transactionId = authorizeCard(eventId);

        Map<String, Object> result = crossingService.complete(eventId, transactionId, 2);

        assertEquals("CLOSED", result.get("gateState"));
        assertEquals(false, result.get("replayed"));

        String gateState = jdbc.queryForObject(
                "SELECT gate_state FROM toll_lanes WHERE id = 2",
                String.class);

        assertEquals("CLOSED", gateState);

        Integer count = jdbc.queryForObject(
                """
                        SELECT COUNT(*) FROM gate_events
                        WHERE transaction_id = ?
                          AND event_type = 'PASSAGE_COMPLETE'
                        """,
                Integer.class,
                transactionId);

        assertEquals(1, count);
    }

    @Test
    void duplicateCrossingMustBeIdempotent() throws Exception {
        UUID eventId = UUID.randomUUID();
        long transactionId = authorizeCard(eventId);

        crossingService.complete(eventId, transactionId, 2);

        Map<String, Object> replay = crossingService.complete(eventId, transactionId, 2);

        assertEquals(true, replay.get("replayed"));

        Integer count = jdbc.queryForObject(
                """
                        SELECT COUNT(*) FROM gate_events
                        WHERE transaction_id = ?
                          AND event_type = 'PASSAGE_COMPLETE'
                        """,
                Integer.class,
                transactionId);

        assertEquals(1, count);
    }

    @Test
    void mismatchedCrossingMustBeRejected() throws Exception {
        UUID eventId = UUID.randomUUID();
        long transactionId = authorizeCard(eventId);

        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> crossingService.complete(
                        eventId,
                        transactionId + 99999,
                        2));

        String gateState = jdbc.queryForObject(
                "SELECT gate_state FROM toll_lanes WHERE id = 2",
                String.class);

        assertEquals("OPEN", gateState);
    }

    @Test
    void concurrentCrossingsMustCompleteOnlyOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        long transactionId = authorizeCard(eventId);

        int requestCount = 5;

        ExecutorService executor = Executors.newFixedThreadPool(requestCount);

        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();

            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();

                    return crossingService.complete(
                            eventId, transactionId, 2);
                }));
            }

            assertTrue(
                    ready.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "Workers did not become ready");

            start.countDown();

            int completed = 0;
            int replayed = 0;

            for (Future<Map<String, Object>> future : futures) {
                Map<String, Object> result = future.get(
                        30, java.util.concurrent.TimeUnit.SECONDS);

                if (Boolean.TRUE.equals(result.get("replayed"))) {
                    replayed++;
                } else {
                    completed++;
                }
            }

            assertEquals(1, completed);
            assertEquals(4, replayed);

            Integer completionCount = jdbc.queryForObject(
                    """
                            SELECT COUNT(*)
                            FROM gate_events
                            WHERE transaction_id = ?
                              AND event_type = 'PASSAGE_COMPLETE'
                            """,
                    Integer.class,
                    transactionId);

            assertEquals(1, completionCount);

            assertEquals(
                    "CLOSED",
                    jdbc.queryForObject(
                            "SELECT gate_state FROM toll_lanes WHERE id = 2",
                            String.class));

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void delayedCrossingMustNotCloseNewerPassage() throws Exception {
        // Vehicle A: authorize and complete passage.
        UUID eventA = UUID.randomUUID();
        long transactionA = authorizeCard(eventA);

        Map<String, Object> firstCompletion = crossingService.complete(eventA, transactionA, 2);

        assertEquals(false, firstCompletion.get("replayed"));

        // Vehicle B: authorize a new passage.
        UUID eventB = UUID.randomUUID();
        long transactionB = authorizeCard(eventB);

        assertNotEquals(transactionA, transactionB);

        assertEquals(
                "OPEN",
                jdbc.queryForObject(
                        "SELECT gate_state FROM toll_lanes WHERE id = 2",
                        String.class));

        // A delayed duplicate crossing from Vehicle A arrives.
        Map<String, Object> delayed = crossingService.complete(eventA, transactionA, 2);

        assertEquals(true, delayed.get("replayed"));

        // Vehicle B's gate must remain OPEN.
        assertEquals(
                "OPEN",
                jdbc.queryForObject(
                        "SELECT gate_state FROM toll_lanes WHERE id = 2",
                        String.class));

        // Vehicle B must not have a completion event.
        Integer completionB = jdbc.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM gate_events
                        WHERE transaction_id = ?
                          AND event_type = 'PASSAGE_COMPLETE'
                        """,
                Integer.class,
                transactionB);

        assertEquals(0, completionB);

        // Vehicle A must still have exactly one completion.
        Integer completionA = jdbc.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM gate_events
                        WHERE transaction_id = ?
                          AND event_type = 'PASSAGE_COMPLETE'
                        """,
                Integer.class,
                transactionA);

        assertEquals(1, completionA);
    }

    @Test
    void restCrossingMustRejectMismatchedTransaction() throws Exception {
        UUID eventId = UUID.randomUUID();
        long transactionId = authorizeCard(eventId);

        String request = mapper.writeValueAsString(
                Map.of(
                        "eventId", eventId.toString(),
                        "transactionId", transactionId + 99999));

        mvc.perform(post("/api/lanes/2/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
                .andExpect(status().isConflict());

        assertEquals(
                "OPEN",
                jdbc.queryForObject(
                        "SELECT gate_state FROM toll_lanes WHERE id = 2",
                        String.class));
    }

    @Test
    void restCrossingMustRejectMissingBody() throws Exception {
        UUID eventId = UUID.randomUUID();
        long transactionId = authorizeCard(eventId);

        mvc.perform(post("/api/lanes/2/complete"))
                .andExpect(status().isBadRequest());

        assertEquals(
                "OPEN",
                jdbc.queryForObject(
                        "SELECT gate_state FROM toll_lanes WHERE id = 2",
                        String.class));

        Integer completions = jdbc.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM gate_events
                        WHERE transaction_id = ?
                          AND event_type = 'PASSAGE_COMPLETE'
                        """,
                Integer.class,
                transactionId);

        assertEquals(0, completions);
    }

    @Test
    void delayedRestCrossingMustNotCloseNewerPassage()
            throws Exception {

        UUID eventA = UUID.randomUUID();
        long transactionA = authorizeCard(eventA);

        String requestA = mapper.writeValueAsString(Map.of(
                "eventId", eventA.toString(),
                "transactionId", transactionA));

        mvc.perform(post("/api/lanes/2/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false));

        UUID eventB = UUID.randomUUID();
        long transactionB = authorizeCard(eventB);

        mvc.perform(post("/api/lanes/2/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        assertEquals(
                "OPEN",
                jdbc.queryForObject(
                        "SELECT gate_state FROM toll_lanes WHERE id = 2",
                        String.class));

        Integer completionsB = jdbc.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM gate_events
                        WHERE transaction_id = ?
                          AND event_type = 'PASSAGE_COMPLETE'
                        """,
                Integer.class,
                transactionB);

        assertEquals(0, completionsB);
    }

    @Test
    void confirmedCashCrossingMustCloseGate() throws Exception {
        UUID eventId = UUID.randomUUID();

        String cashRequest = mapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "plateNumber", "XYZ-456",
                "laneId", 1));

        MvcResult created = mvc.perform(
                post("/api/cash/passages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cashRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CASH"))
                .andReturn();

        long transactionId = mapper.readTree(
                created.getResponse().getContentAsString()).path("transactionId").asLong();

        mvc.perform(
                post("/api/cash/passages/" + transactionId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                Map.of("cashReceived", 500))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.gateState").value("OPEN"));

        String crossingRequest = mapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "transactionId", transactionId));

        mvc.perform(post("/api/lanes/1/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(crossingRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateState").value("CLOSED"))
                .andExpect(jsonPath("$.replayed").value(false));

        Integer completionCount = jdbc.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM gate_events
                        WHERE transaction_id = ?
                          AND event_type = 'PASSAGE_COMPLETE'
                        """,
                Integer.class,
                transactionId);

        assertEquals(1, completionCount);

        assertEquals(
                "CLOSED",
                jdbc.queryForObject(
                        "SELECT gate_state FROM toll_lanes WHERE id = 1",
                        String.class));
    }

    @Test
    void pendingCashCrossingMustBeRejected() throws Exception {
        UUID eventId = UUID.randomUUID();
        long tx = createCashPassage(eventId);

        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> crossingService.complete(eventId, tx, 1));

        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT gate_state FROM toll_lanes WHERE id = 1",
                String.class));

        Integer count = jdbc.queryForObject(
                """
                        SELECT COUNT(*) FROM gate_events
                        WHERE transaction_id = ?
                          AND event_type = 'PASSAGE_COMPLETE'
                        """,
                Integer.class, tx);

        assertEquals(0, count);
    }

    @Test
    void mismatchedCashCrossingMustBeRejected() throws Exception {
        UUID eventId = UUID.randomUUID();
        long tx = createCashPassage(eventId);
        confirmCashPassage(tx);

        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> crossingService.complete(eventId, tx + 99999, 1));

        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT gate_state FROM toll_lanes WHERE id = 1",
                String.class));
    }

    @Test
    void duplicateCashCrossingMustBeIdempotent() throws Exception {
        UUID eventId = UUID.randomUUID();
        long tx = createCashPassage(eventId);
        confirmCashPassage(tx);

        Map<String, Object> first = crossingService.complete(eventId, tx, 1);

        Map<String, Object> duplicate = crossingService.complete(eventId, tx, 1);

        assertEquals(false, first.get("replayed"));
        assertEquals(true, duplicate.get("replayed"));

        Integer count = jdbc.queryForObject(
                """
                        SELECT COUNT(*) FROM gate_events
                        WHERE transaction_id = ?
                          AND event_type = 'PASSAGE_COMPLETE'
                        """,
                Integer.class, tx);

        assertEquals(1, count);

        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT gate_state FROM toll_lanes WHERE id = 1",
                String.class));
    }

    @Test
    void delayedCashCrossingMustNotCloseNewerPassage()
            throws Exception {

        UUID eventA = UUID.randomUUID();
        long txA = createCashPassage(eventA);
        confirmCashPassage(txA);

        crossingService.complete(eventA, txA, 1);

        UUID eventB = UUID.randomUUID();
        long txB = createCashPassage(eventB);
        confirmCashPassage(txB);

        Map<String, Object> delayed = crossingService.complete(eventA, txA, 1);

        assertEquals(true, delayed.get("replayed"));

        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT gate_state FROM toll_lanes WHERE id = 1",
                String.class));

        Integer countB = jdbc.queryForObject(
                """
                        SELECT COUNT(*) FROM gate_events
                        WHERE transaction_id = ?
                          AND event_type = 'PASSAGE_COMPLETE'
                        """,
                Integer.class, txB);

        assertEquals(0, countB);
    }

}
