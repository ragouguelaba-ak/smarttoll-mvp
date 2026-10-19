package com.smarttoll;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/gate-events")
@CrossOrigin(origins = "http://localhost:4200")
public class GateEventController {

    private final GateEventStream stream;

    public GateEventController(GateEventStream stream) {
        this.stream = stream;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        return stream.subscribe();
    }
}