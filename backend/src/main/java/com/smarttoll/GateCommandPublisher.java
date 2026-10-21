package com.smarttoll;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.UUID;

@Component
public class GateCommandPublisher {

    private final ObjectProvider<MessageChannel> outputChannel;
    private final ObjectMapper mapper;

    public GateCommandPublisher(
            @org.springframework.beans.factory.annotation.Qualifier(
                    "mqttOutputChannel")
            ObjectProvider<MessageChannel> outputChannel,
            ObjectMapper mapper) {

        this.outputChannel = outputChannel;
        this.mapper = mapper;
    }

    public void publishAfterCommit(
            long laneId,
            UUID eventId,
            long transactionId,
            String command) {

        if (!TransactionSynchronizationManager
                .isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Gate command requires an active transaction");
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            publish(laneId, eventId,
                                    transactionId, command);
                        } catch (Exception ex) {
                            System.err.println(
                                    "MQTT gate command failed: "
                                            + ex.getMessage());
                        }
                    }
                });
    }

    private void publish(
            long laneId,
            UUID eventId,
            long transactionId,
            String command) throws Exception {

        MessageChannel channel = outputChannel.getIfAvailable();

        if (channel == null) {
            return; // MQTT disabled in integration tests.
        }

        String topic =
                "smarttoll/lanes/" + laneId + "/gate-command";

        String payload = mapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "transactionId", transactionId,
                "status", "AUTHORIZED",
                "command", command
        ));

        boolean accepted = channel.send(
                MessageBuilder.withPayload(payload)
                        .setHeader(MqttHeaders.TOPIC, topic)
                        .build());

        if (!accepted) {
            throw new IllegalStateException(
                    "MQTT output channel rejected gate command");
        }

        System.out.println(
                "MQTT gate command published: "
                        + topic + " -> " + command);
    }
}