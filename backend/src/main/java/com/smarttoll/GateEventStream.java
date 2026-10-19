package com.smarttoll;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GateEventStream {

    private final Set<SseEmitter> clients =
            ConcurrentHashMap.newKeySet();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);

        clients.add(emitter);

        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> clients.remove(emitter));
        emitter.onError(error -> clients.remove(emitter));

        try {
            emitter.send(
                SseEmitter.event()
                    .name("connected")
                    .data(Map.of(
                        "message", "SmartToll SSE connected"
                    ))
            );
        } catch (Exception e) {
            clients.remove(emitter);
            // Do not call completeWithError() here after a failed send.
        }

        return emitter;
    }

    public void publish(long laneId, String state) {

        Map<String, Object> payload = Map.of(
            "laneId", laneId,
            "gateState", state,
            "timestamp", System.currentTimeMillis()
        );

        for (SseEmitter client : clients) {
            try {
                client.send(
                    SseEmitter.event()
                        .name("gate-status")
                        .data(payload)
                );
            } catch (Exception e) {
                // Remove the failed connection.
                // Tomcat handles cleanup after a transport error.
                clients.remove(client);
            }
        }
    }
}