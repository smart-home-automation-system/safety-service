package cloud.cholewa.safety.api;

import cloud.cholewa.safety.api.model.DetectorReply;
import cloud.cholewa.safety.api.model.SafetyReply;
import cloud.cholewa.safety.detector.SafetyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

//The reads of the fire detectors (HAS-460), base path /home/safety: every detector and base, the detectors of a room,
//one detector. Reads only - nothing here commands a detector
@RestController
@RequiredArgsConstructor
public class SafetyController {

    private final SafetyService service;

    @GetMapping
    Mono<SafetyReply> all() {
        return service.all();
    }

    @GetMapping("/rooms/{room}")
    Mono<List<DetectorReply>> room(@PathVariable final String room) {
        return service.room(room);
    }

    @GetMapping("/{id}")
    Mono<DetectorReply> detector(@PathVariable final long id) {
        return service.detector(id);
    }
}
