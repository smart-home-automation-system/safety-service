package cloud.cholewa.safety.automation;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.safety.database.AutomationStore;
import cloud.cholewa.safety.registry.Detector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The reactions to a newly opened fire alarm episode (HAS-468), each behind its switch, once per episode, nothing
 * switched back at its end: the lights of the room of the detector and of both halls on, the blinds of its room up,
 * every air conditioner that is on off. The decision is logged with the switches off too (nothing sent then).
 * <p>
 * <b>Never fails, never stalls the alarm</b>: every call has its own outcome, a call that fails is logged and the rest
 * go on, all of them are bounded at {@link #BOUND} together; each call is a row of {@code safety_reaction_run}, written
 * within {@link #STORE_WITHIN} or a WARN. The furnace is not touched - boiler-service has no API for it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FireReactions {

    static final Duration BOUND = Duration.ofSeconds(30);
    static final Duration STORE_WITHIN = Duration.ofSeconds(5);

    private final AutomationService automation;
    private final HouseServicesClient client;
    private final AutomationStore store;
    private final Clock clock;

    /** {@code episode} null when the episode was not stored - the reactions go all the same. */
    public Mono<Void> react(final Long episode, final Detector detector) {
        return automation.forAlarm()
            .switchIfEmpty(Mono.fromRunnable(() -> log.warn(
                "The fire alarm of the detector {}: the switches of the reactions are not known - nothing sent",
                detector.id())))
            .doOnNext(switches -> log.info("The fire alarm of the detector {} (episode {}): reactions {}{}",
                detector.id(), episode, switches, switches.anyOn() ? "" : " - all off, nothing sent"))
            .flatMap(switches -> Mono.when(
                lights(switches, episode, detector),
                blinds(switches, episode, detector),
                aircon(switches, episode)))
            .timeout(BOUND)
            .onErrorResume(failure -> {
                log.warn("The reactions to the fire alarm of the detector {} were cut off: {}", detector.id(),
                    failure.toString());
                return Mono.empty();
            });
    }

    private Mono<Void> lights(final AutomationSwitches switches, final Long episode, final Detector detector) {
        return Mono.just(switches)
            .filter(on -> on.on(Reaction.LIGHTS_ON))
            .flatMapMany(on -> Flux.fromIterable(lightRooms(detector.room())))
            .flatMap(room -> client.lightsOn(room)
                .flatMap(outcome -> stored(episode, Reaction.LIGHTS_ON, room.getValue(), outcome)))
            .then();
    }

    private Mono<Void> blinds(final AutomationSwitches switches, final Long episode, final Detector detector) {
        return Mono.justOrEmpty(detector.room())
            .filter(room -> switches.on(Reaction.BLINDS_UP))
            .flatMap(room -> client.blindsUp(room)
                .flatMap(outcome -> stored(episode, Reaction.BLINDS_UP, room.getValue(), outcome)));
    }

    private Mono<Void> aircon(final AutomationSwitches switches, final Long episode) {
        return Mono.just(switches)
            .filter(on -> on.on(Reaction.AIRCON_OFF))
            .flatMapMany(on -> client.airconUnitsOn()
                .onErrorResume(failure -> {
                    log.warn("The air conditioners were not read for the fire alarm: {}", failure.toString());
                    return stored(episode, Reaction.AIRCON_OFF, null, ReactionOutcome.FAILED).then(Mono.<Long>empty());
                }))
            .flatMap(id -> client.airconOff(id)
                .flatMap(outcome -> stored(episode, Reaction.AIRCON_OFF, String.valueOf(id), outcome)))
            .then();
    }

    //the way out: the room of the detector first, then the halls of both floors, each once; a detector without a room
    //lights the halls alone
    static Set<RoomName> lightRooms(final RoomName room) {
        final Set<RoomName> rooms = new LinkedHashSet<>();
        Stream.concat(Stream.ofNullable(room), Stream.of(RoomName.HALL_DOWN, RoomName.HALL_UP)).forEach(rooms::add);
        return rooms;
    }

    private Mono<Void> stored(final Long episode, final Reaction reaction, final String target,
                              final ReactionOutcome outcome) {
        return store.insertRun(episode, reaction, target, clock.instant(), outcome)
            .timeout(STORE_WITHIN)
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The reaction {} on {} was not stored: {}", reaction.getValue(), target, failure.toString())));
    }
}
