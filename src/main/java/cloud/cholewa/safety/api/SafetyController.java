package cloud.cholewa.safety.api;

import cloud.cholewa.safety.api.model.AlarmReply;
import cloud.cholewa.safety.api.model.DetectorReply;
import cloud.cholewa.safety.api.model.SafetyReply;
import cloud.cholewa.safety.detector.AlarmService;
import cloud.cholewa.safety.detector.SafetyService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

//The reads of the fire detectors (HAS-460), base path /home/safety: every detector and base, the detectors of a room,
//one detector; since HAS-461 the fire alarm episodes of a period. Reads only - nothing here commands a detector
@RestController
@RequiredArgsConstructor
public class SafetyController {

    private final SafetyService service;
    private final AlarmService alarmService;

    @GetMapping
    Mono<SafetyReply> all() {
        return service.all();
    }

    //collected, not streamed: a database failure halfway is an error answer, never a cut array behind a 200
    @GetMapping("/alarms")
    Mono<List<AlarmReply>> alarms(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime to
    ) {
        return alarmService.alarms(from, to).collectList();
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
