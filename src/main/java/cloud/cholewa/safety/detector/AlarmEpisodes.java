package cloud.cholewa.safety.detector;

import cloud.cholewa.safety.automation.FireReactions;
import cloud.cholewa.safety.database.AlarmStore;
import cloud.cholewa.safety.household.HouseholdNotifier;
import cloud.cholewa.safety.registry.Detector;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The fire alarm episodes (HAS-461). Which detector has one open is held in memory - read back at the start - and decides
 * alone: a Detected of a detector without one opens it, a Detected of one with an open episode is no new episode (the
 * base repeats the state), a Cleared closes it, a Cleared without one is ignored (the base republishes Cleared of every
 * detector every hour). The decision is taken in the listener, atomically per detector; the work runs off it, one
 * episode after the other in the order decided: the row first, then the household told, then the mark of the message.
 * <p>
 * <b>The household is told whatever the database says</b>: a database away or slow (beyond {@link #STORE_WITHIN}) is a
 * WARN, the alarm is told all the same and only its mark is missing; the open episodes in memory keep a repeated Detected from telling it twice. A
 * notification never fails nor stalls an episode - {@link HouseholdNotifier} answers false within its bound.
 * <p>
 * Since HAS-468 a newly opened episode also runs the reactions of {@link FireReactions} (behind their switches), at the
 * same time as the household is told - neither waits for the other, both are bounded at 30 s and never fail.
 */
@Slf4j
@Component
public class AlarmEpisodes {

    private static final Duration EMIT_RETRY = Duration.ofSeconds(1);
    //how long the household waits for the row of its alarm: the store retries for up to a minute and a half, and a fire
    //alarm is told within seconds whatever the database does - a write cut off here may still land, only its mark is lost
    static final Duration STORE_WITHIN = Duration.ofSeconds(5);
    //the id of an episode the database did not take: told all the same, never marked
    private static final long NOT_STORED = -1L;

    private final AlarmStore store;
    private final HouseholdNotifier notifier;
    private final FireReactions reactions;
    private final Clock clock;
    private final Set<Long> open = ConcurrentHashMap.newKeySet();
    private final Sinks.Many<Mono<Void>> work = Sinks.many().unicast().onBackpressureBuffer();
    private final Disposable subscription;

    public AlarmEpisodes(final AlarmStore store, final HouseholdNotifier notifier, final FireReactions reactions,
                         final Clock clock) {
        this.store = store;
        this.notifier = notifier;
        this.reactions = reactions;
        this.clock = clock;
        this.subscription = work.asFlux()
            .concatMap(job -> job.onErrorResume(failure -> {
                log.warn("A fire alarm episode failed: {}", failure.toString());
                return Mono.empty();
            }))
            .subscribe();
    }

    /** What the smokealarm of a detector says now - every message, repeated or not. */
    public void alarm(final Detector detector, final boolean detected) {
        //the moment it arrived, not the moment its job runs behind another
        final Instant at = clock.instant();
        if (detected && open.add(detector.id())) {
            emit(Mono.defer(() -> opened(detector, at)));
        } else if (!detected && open.remove(detector.id())) {
            emit(Mono.defer(() -> closed(detector, at)));
        } else if (!detected) {
            log.debug("Cleared of the detector {} without an open alarm - nothing to close", detector.id());
        }
    }

    /** A detector with an episode stored open - read back at the start, before the first message. */
    public void restoreOpen(final long detectorId) {
        open.add(detectorId);
    }

    public boolean isOpen(final long detectorId) {
        return open.contains(detectorId);
    }

    private Mono<Void> opened(final Detector detector, final Instant at) {
        log.warn("A fire alarm of the detector {} ({}, {})", detector.id(), detector.name(), detector.room());
        return store.open(detector.id(), detector.room() == null ? null : detector.room().getValue(), at)
            .timeout(STORE_WITHIN)
            .onErrorResume(failure -> {
                log.warn("The fire alarm of the detector {} was not stored: {}", detector.id(), failure.toString());
                return Mono.empty();
            })
            .defaultIfEmpty(NOT_STORED)
            .flatMap(id -> Mono.when(
                notifier.fireAlarm(detector, at)
                    .filter(told -> told && id != NOT_STORED)
                    .flatMap(told -> store.markAlarmTold(id, clock.instant()).timeout(STORE_WITHIN))
                    .onErrorResume(failure -> {
                        log.warn("The fire alarm of the detector {} was told, its mark not stored: {}", detector.id(),
                            failure.toString());
                        return Mono.empty();
                    }),
                reactions.react(id == NOT_STORED ? null : id, detector)));
    }

    private Mono<Void> closed(final Detector detector, final Instant at) {
        log.info("The fire alarm of the detector {} ({}) is over", detector.id(), detector.name());
        return store.close(detector.id(), at)
            .timeout(STORE_WITHIN)
            .onErrorResume(failure -> {
                log.warn("The end of the fire alarm of the detector {} was not stored: {}", detector.id(),
                    failure.toString());
                return Mono.empty();
            })
            .defaultIfEmpty(NOT_STORED)
            .flatMap(id -> notifier.fireAlarmOver(detector, at)
                .filter(told -> told && id != NOT_STORED)
                .flatMap(told -> store.markOverTold(id, clock.instant()).timeout(STORE_WITHIN))
                .onErrorResume(failure -> {
                    log.warn("The end of the fire alarm of the detector {} was told, its mark not stored: {}",
                        detector.id(), failure.toString());
                    return Mono.empty();
                }));
    }

    //the listener takes three messages at once: a concurrent emission is retried until the sink takes it
    private void emit(final Mono<Void> job) {
        work.emitNext(job, Sinks.EmitFailureHandler.busyLooping(EMIT_RETRY));
    }

    @PreDestroy
    void stop() {
        subscription.dispose();
    }
}
