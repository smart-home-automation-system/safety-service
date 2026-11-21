package cloud.cholewa.safety.api;

import cloud.cholewa.safety.api.model.AlarmReply;
import cloud.cholewa.safety.api.model.CommandRecordReply;
import cloud.cholewa.safety.api.model.CommandReply;
import cloud.cholewa.safety.api.model.DetectorReply;
import cloud.cholewa.safety.api.model.SafetyReply;
import cloud.cholewa.safety.api.model.StatusReply;
import cloud.cholewa.safety.command.CommandService;
import cloud.cholewa.safety.command.CommandSource;
import cloud.cholewa.safety.command.DetectorCommand;
import cloud.cholewa.safety.detector.AlarmService;
import cloud.cholewa.safety.detector.SafetyService;
import cloud.cholewa.safety.monitor.StatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

//The reads of the fire detectors (HAS-460), base path /home/safety: every detector and base, the detectors of a room,
//one detector; since HAS-461 the fire alarm episodes of a period, since HAS-463 the technical state, since HAS-464 the
//mute and the self test of a detector and its last commands
@RestController
@RequiredArgsConstructor
public class SafetyController {

    private final SafetyService service;
    private final AlarmService alarmService;
    private final StatusService statusService;
    private final CommandService commandService;

    //the technical state (HAS-463): the bases, the detectors in trouble, the open alerts of the technical monitor
    @GetMapping("/status")
    Mono<StatusReply> status() {
        return statusService.status();
    }

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

    //HAS-464: 202 once the command was sent - whether the base did it, the reads tell (lastCommandConfirmed)
    @PostMapping("/{id}/mute")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<CommandReply> mute(@PathVariable final long id,
                            @RequestParam(defaultValue = "dashboard") final CommandSource source) {
        return commandService.command(id, DetectorCommand.MUTE, source);
    }

    @PostMapping("/{id}/self-test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<CommandReply> selfTest(@PathVariable final long id,
                                @RequestParam(defaultValue = "dashboard") final CommandSource source) {
        return commandService.command(id, DetectorCommand.SELF_TEST, source);
    }

    @GetMapping("/{id}/commands")
    Mono<List<CommandRecordReply>> commands(@PathVariable final long id) {
        return commandService.history(id);
    }
}
