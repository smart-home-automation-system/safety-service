package cloud.cholewa.safety.automation;

import cloud.cholewa.safety.api.model.AutomationReply;
import cloud.cholewa.safety.api.model.ReactionRunReply;
import cloud.cholewa.safety.database.AutomationStore;
import cloud.cholewa.safety.detector.AlarmService;
import cloud.cholewa.safety.error.SafetyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * The switches of the reactions to a fire alarm (HAS-468): read and written whole ({@code GET} / {@code PUT
 * /automation}), and the reactions sent in a period ({@code GET /automation/runs}). A body is the three switches, each
 * {@code true} or {@code false}, and nothing else - {@code INVALID_AUTOMATION} otherwise.
 * <p>
 * An alarm reads the switches from the database, bounded at {@link #READ_WITHIN}; a database away or slow falls back on
 * the switches last read or written, and with none known nothing is sent - a reaction is never sent on a guess.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationService {

    static final Duration READ_WITHIN = Duration.ofSeconds(5);

    private static final Set<String> NAMES = Arrays.stream(Reaction.values()).map(Reaction::getValue)
        .collect(Collectors.toCollection(TreeSet::new));

    private final AutomationStore store;
    private final Clock clock;
    private final AtomicReference<AutomationSwitches> known = new AtomicReference<>();

    public Mono<AutomationReply> automation() {
        return store.read().doOnNext(known::set).map(AutomationService::reply);
    }

    public Mono<AutomationReply> save(final Map<String, Object> body) {
        return Mono.justOrEmpty(problemOf(body))
            .flatMap(problem -> Mono.<AutomationSwitches>error(new SafetyException.InvalidAutomation(problem)))
            .switchIfEmpty(Mono.fromSupplier(() -> new AutomationSwitches(
                (Boolean) body.get(Reaction.LIGHTS_ON.getValue()),
                (Boolean) body.get(Reaction.BLINDS_UP.getValue()),
                (Boolean) body.get(Reaction.AIRCON_OFF.getValue()))))
            .flatMap(switches -> store.write(switches, clock.instant())
                .then(Mono.fromRunnable(() -> {
                    known.set(switches);
                    log.info("The reactions to a fire alarm switched: {}", switches);
                }))
                .thenReturn(reply(switches)));
    }

    /** The switches an alarm acts on; empty when none is known. */
    public Mono<AutomationSwitches> forAlarm() {
        return store.read()
            .timeout(READ_WITHIN)
            .doOnNext(known::set)
            .onErrorResume(failure -> {
                log.warn("The switches of the reactions were not read, the last known are taken: {}", failure.toString());
                return Mono.justOrEmpty(known.get());
            });
    }

    public Flux<ReactionRunReply> runs(final LocalDateTime from, final LocalDateTime to) {
        return Mono.justOrEmpty(AlarmService.problemOf(from, to))
            .flatMap(problem -> Mono.<Void>error(new SafetyException.InvalidRange(problem)))
            .thenMany(Flux.defer(() -> store.runs(instant(from), instant(to))))
            .map(row -> new ReactionRunReply(row.episode(), row.reaction(), row.target(), house(row.sentAt()),
                row.outcome()));
    }

    //the three switches, each a boolean, and nothing else
    static Optional<String> problemOf(final Map<String, Object> body) {
        if (body == null) {
            return Optional.of("The body must be the switches " + NAMES);
        }
        final Set<String> unknown = new TreeSet<>(body.keySet());
        unknown.removeAll(NAMES);
        if (!unknown.isEmpty()) {
            return Optional.of("Unknown fields " + unknown + " - the switches are " + NAMES);
        }
        final Set<String> missing = NAMES.stream().filter(name -> !(body.get(name) instanceof Boolean))
            .collect(Collectors.toCollection(TreeSet::new));
        if (!missing.isEmpty()) {
            return Optional.of("Every switch must be true or false: " + missing);
        }
        return Optional.empty();
    }

    private static AutomationReply reply(final AutomationSwitches switches) {
        return new AutomationReply(switches.lightsOn(), switches.blindsUp(), switches.airconOff());
    }

    private Instant instant(final LocalDateTime houseTime) {
        return houseTime.atZone(clock.getZone()).toInstant();
    }

    private LocalDateTime house(final Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone());
    }
}
