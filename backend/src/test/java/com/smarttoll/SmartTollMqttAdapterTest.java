package com.smarttoll;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmartTollMqttAdapterTest {

    @Test
    void rejectsCrossingWhenPayloadLaneDoesNotMatchTopic() throws Exception {
        var payments = mock(DualLanePaymentController.class);
        var crossingService = mock(VehicleCrossingService.class);

        var adapter = new SmartTollMqttAdapter(
                payments,
                crossingService,
                new ObjectMapper());

        String payload = """
                {
                  "eventId": "%s",
                  "transactionId": 123,
                  "laneId": 1
                }
                """.formatted(UUID.randomUUID());

        var message = MessageBuilder.withPayload(payload).build();

        Method handler = SmartTollMqttAdapter.class.getDeclaredMethod(
                "handleVehicleCrossed",
                org.springframework.messaging.Message.class,
                long.class);

        handler.setAccessible(true);

        InvocationTargetException exception =
                assertThrows(InvocationTargetException.class,
                        () -> handler.invoke(adapter, message, 2L));

        assertInstanceOf(
                IllegalArgumentException.class,
                exception.getCause());

        verifyNoInteractions(payments, crossingService);
    }

    @Test
    void rejectsCrossingWithInvalidTransactionId() throws Exception {
        var payments = mock(DualLanePaymentController.class);
        var crossingService = mock(VehicleCrossingService.class);

        var adapter = new SmartTollMqttAdapter(
                payments,
                crossingService,
                new ObjectMapper());

        String payload = """
                {
                  "eventId": "%s",
                  "transactionId": 0,
                  "laneId": 2
                }
                """.formatted(UUID.randomUUID());

        var message = MessageBuilder.withPayload(payload).build();

        Method handler = SmartTollMqttAdapter.class.getDeclaredMethod(
                "handleVehicleCrossed",
                org.springframework.messaging.Message.class,
                long.class);

        handler.setAccessible(true);

        InvocationTargetException exception =
                assertThrows(InvocationTargetException.class,
                        () -> handler.invoke(adapter, message, 2L));

        assertInstanceOf(
                IllegalArgumentException.class,
                exception.getCause());

        verifyNoInteractions(payments, crossingService);
    }


    @Test
    void replayedCrossingDoesNotPublishCloseCommand() throws Exception {
        var payments = mock(DualLanePaymentController.class);
        var crossingService = mock(VehicleCrossingService.class);

        var adapter = spy(new SmartTollMqttAdapter(
                payments,
                crossingService,
                new ObjectMapper()));

        var outputChannel =
                mock(org.springframework.messaging.MessageChannel.class);

        doReturn(outputChannel).when(adapter).mqttOutputChannel();

        UUID eventId = UUID.randomUUID();
        long transactionId = 123L;

        when(crossingService.complete(eventId, transactionId, 2L))
                .thenReturn(java.util.Map.of("replayed", true));

        String payload = """
                {
                  "eventId": "%s",
                  "transactionId": 123,
                  "laneId": 2
                }
                """.formatted(eventId);

        var message = MessageBuilder.withPayload(payload).build();

        Method handler = SmartTollMqttAdapter.class.getDeclaredMethod(
                "handleVehicleCrossed",
                org.springframework.messaging.Message.class,
                long.class);

        handler.setAccessible(true);
        handler.invoke(adapter, message, 2L);

        verify(crossingService, times(1))
                .complete(eventId, transactionId, 2L);

        verifyNoInteractions(outputChannel, payments);
    }


    @Test
    void successfulCrossingPublishesOneCloseCommand() throws Exception {
        var payments = mock(DualLanePaymentController.class);
        var crossingService = mock(VehicleCrossingService.class);

        var adapter = spy(new SmartTollMqttAdapter(
                payments, crossingService, new ObjectMapper()));

        var outputChannel =
                mock(org.springframework.messaging.MessageChannel.class);

        doReturn(outputChannel).when(adapter).mqttOutputChannel();

        UUID eventId = UUID.randomUUID();
        long transactionId = 123L;

        when(crossingService.complete(eventId, transactionId, 2L))
                .thenReturn(java.util.Map.of("replayed", false));

        String payload = """
                {
                  "eventId": "%s",
                  "transactionId": 123,
                  "laneId": 2
                }
                """.formatted(eventId);

        var message = MessageBuilder.withPayload(payload).build();

        Method handler = SmartTollMqttAdapter.class.getDeclaredMethod(
                "handleVehicleCrossed",
                org.springframework.messaging.Message.class,
                long.class);

        handler.setAccessible(true);
        handler.invoke(adapter, message, 2L);

        var captor = org.mockito.ArgumentCaptor.forClass(
                org.springframework.messaging.Message.class);

        verify(outputChannel, times(1)).send(captor.capture());

        var published = captor.getValue();

        assertEquals(
                "smarttoll/lanes/2/gate-command",
                published.getHeaders().get("mqtt_topic"));

        var json = new ObjectMapper().readTree(
                published.getPayload().toString());

        assertEquals(eventId.toString(),
                json.path("eventId").asText());
        assertEquals(transactionId,
                json.path("transactionId").asLong());
        assertEquals("CLOSED",
                json.path("command").asText());
        assertEquals("PASSAGE_COMPLETE",
                json.path("status").asText());

        verify(crossingService, times(1))
                .complete(eventId, transactionId, 2L);

        verifyNoInteractions(payments);
    }


    @Test
    void rejectsCrossingWithMissingEventId() throws Exception {
        var payments = mock(DualLanePaymentController.class);
        var crossingService = mock(VehicleCrossingService.class);

        var adapter = new SmartTollMqttAdapter(
                payments, crossingService, new ObjectMapper());

        String payload = """
                {
                  "eventId": null,
                  "transactionId": 123,
                  "laneId": 2
                }
                """;

        var message = MessageBuilder.withPayload(payload).build();

        Method handler = SmartTollMqttAdapter.class.getDeclaredMethod(
                "handleVehicleCrossed",
                org.springframework.messaging.Message.class,
                long.class);

        handler.setAccessible(true);

        InvocationTargetException exception =
                assertThrows(InvocationTargetException.class,
                        () -> handler.invoke(adapter, message, 2L));

        assertInstanceOf(
                IllegalArgumentException.class,
                exception.getCause());

        verifyNoInteractions(payments, crossingService);
    }

}
