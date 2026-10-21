package com.smarttoll;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import java.util.UUID;

import java.util.Map;

@Configuration
@ConditionalOnProperty(prefix = "smarttoll.mqtt", name = "enabled", havingValue = "true")
public class SmartTollMqttAdapter {

        private final DualLanePaymentController payments;
        private final VehicleCrossingService crossingService;
        private final ObjectMapper mapper;

        public SmartTollMqttAdapter(
                        DualLanePaymentController payments,
                        VehicleCrossingService crossingService,
                        ObjectMapper mapper) {
                this.payments = payments;
                this.crossingService = crossingService;
                this.mapper = mapper;
        }

        @Value("${smarttoll.mqtt.url}")
        private String brokerUrl;

        @Value("${smarttoll.mqtt.client-id}")
        private String clientId;

        public record VehicleCrossedEvent(
                        UUID eventId,
                        long transactionId,
                        long laneId) {
        }

        private void handleVehicleCrossed(Message<?> message, long topicLaneId)
                        throws Exception {

                var crossing = mapper.readValue(
                                message.getPayload().toString(),
                                VehicleCrossedEvent.class);

                if (crossing.eventId() == null
                                || crossing.transactionId() <= 0
                                || crossing.laneId() != topicLaneId) {
                        throw new IllegalArgumentException(
                                        "Invalid crossing event or MQTT lane mismatch");
                }

                Map<String, Object> result = crossingService.complete(
                                crossing.eventId(),
                                crossing.transactionId(),
                                topicLaneId);

                if (Boolean.TRUE.equals(result.get("replayed"))) {
                        System.out.println(
                                        "Duplicate crossing ignored: " + crossing.eventId());
                        return;
                }

                String response = mapper.writeValueAsString(
                                Map.of(
                                                "eventId", crossing.eventId().toString(),
                                                "transactionId", crossing.transactionId(),
                                                "status", "PASSAGE_COMPLETE",
                                                "command", "CLOSED"));

                String commandTopic = "smarttoll/lanes/" + topicLaneId + "/gate-command";

                mqttOutputChannel().send(
                                MessageBuilder.withPayload(response)
                                                .setHeader("mqtt_topic", commandTopic)
                                                .build());

                System.out.println(
                                "Vehicle passage completed on lane "
                                                + topicLaneId + ": "
                                                + crossing.transactionId());
        }

        @Bean
        public DefaultMqttPahoClientFactory mqttClientFactory() {
                var options = new MqttConnectOptions();
                options.setServerURIs(new String[] { brokerUrl });
                options.setAutomaticReconnect(true);

                var factory = new DefaultMqttPahoClientFactory();
                factory.setConnectionOptions(options);
                return factory;
        }

        @Bean
        public MessageChannel mqttInputChannel() {
                return new DirectChannel();
        }

        @Bean
        public MessageChannel mqttOutputChannel() {
                return new DirectChannel();
        }

        @Bean
        public MqttPahoMessageDrivenChannelAdapter mqttInbound(
                        DefaultMqttPahoClientFactory factory) {

                var adapter = new MqttPahoMessageDrivenChannelAdapter(
                                clientId + "-subscriber",
                                factory,
                                "smarttoll/lanes/2/vehicle-detected",
                                "smarttoll/lanes/2/vehicle-crossed",
                                "smarttoll/lanes/1/vehicle-crossed");

                adapter.setQos(1);
                adapter.setOutputChannel(mqttInputChannel());

                return adapter;
        }

        @Bean
        @ServiceActivator(inputChannel = "mqttOutputChannel")
        public MessageHandler mqttOutbound(
                        DefaultMqttPahoClientFactory factory) {

                var handler = new MqttPahoMessageHandler(
                                clientId + "-publisher",
                                factory);

                handler.setAsync(false);
                handler.setDefaultQos(1);

                return handler;
        }

        @Bean
        @ServiceActivator(inputChannel = "mqttInputChannel")
        public MessageHandler mqttVehicleHandler() {

                return message -> {
                        try {
                                String topic = message.getHeaders().get(
                                                MqttHeaders.RECEIVED_TOPIC,
                                                String.class);

                                // Handle vehicle-crossing events.
                                if ("smarttoll/lanes/1/vehicle-crossed".equals(topic)) {
                                        handleVehicleCrossed(message, 1);
                                        return;
                                }

                                if ("smarttoll/lanes/2/vehicle-crossed".equals(topic)) {
                                        handleVehicleCrossed(message, 2);
                                        return;
                                }

                                // Ignore unknown topics.
                                if (!"smarttoll/lanes/2/vehicle-detected".equals(topic)) {
                                        System.err.println(
                                                        "Unexpected MQTT topic: " + topic);
                                        return;
                                }

                                // Handle RFID vehicle detection.
                                String payload = message.getPayload().toString();

                                var request = mapper.readValue(
                                                payload,
                                                DualLanePaymentController.CardPassageRequest.class);

                                if (request.laneId() != 2) {
                                        throw new IllegalArgumentException(
                                                        "MQTT adapter accepts lane 2 only");
                                }

                                Map<String, Object> result = payments.cardPassage(request);

                                // Never reopen a gate for a replayed payment event.
                                if (Boolean.TRUE.equals(result.get("replayed"))) {
                                        System.out.println(
                                                        "MQTT replay ignored: " + request.eventId());
                                        return;
                                }

                                // cardPassage() is the authoritative publisher of
                                // authorized MQTT OPEN commands.
                                System.out.println(
                                                "RFID event processed: "
                                                                + request.eventId()
                                                                + ", status: "
                                                                + result.get("status"));

                        } catch (Exception ex) {
                                System.err.println(
                                                "MQTT event processing failed: " + ex.getMessage());
                        }
                };
        }
}