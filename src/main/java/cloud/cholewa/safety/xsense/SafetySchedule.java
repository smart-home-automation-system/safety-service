package cloud.cholewa.safety.xsense;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.safety.registry.DetectorRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The two passes of the service: the detectors of the registry read again ({@code safety.registry.refresh-every}, the
 * first at the start), and the birth message sent again ({@code safety.base.resync-every}). Once the registry is known
 * the pass of the registry sends the birth message until the broker took one - the messages of a base are dropped until
 * the registry is known, so the base is asked to republish only then, and a first send that failed is not left to the
 * next resync an hour later. Both bounded below the shortest delay they may be given.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SafetySchedule {

    static final Duration REGISTRY_LIMIT = Duration.ofSeconds(50);
    static final Duration RESYNC_LIMIT = Duration.ofSeconds(50);

    private final DetectorRegistry registry;
    private final BirthMessage birthMessage;

    private final AtomicBoolean birthSent = new AtomicBoolean();

    @Scheduled(fixedDelayString = "${safety.registry.refresh-every}", initialDelay = 0)
    Mono<Void> refreshRegistry() {
        return ScheduledPass.bounded("registry of the fire detectors", REGISTRY_LIMIT, () -> registry.refresh()
            .onErrorResume(failure -> Mono.just(false).doOnNext(ignored ->
                log.warn("The fire detectors of the registry were not read again: {}", failure.toString())))
            .filter(ignored -> registry.isKnown() && !birthSent.get())
            .flatMap(ignored -> sendBirth()));
    }

    @Scheduled(fixedDelayString = "${safety.base.resync-every}", initialDelayString = "${safety.base.resync-every}")
    Mono<Void> resyncBase() {
        return ScheduledPass.bounded("birth message to the X-Sense bases", RESYNC_LIMIT, this::sendBirth);
    }

    private Mono<Boolean> sendBirth() {
        return birthMessage.send().doOnNext(sent -> birthSent.compareAndSet(false, sent));
    }
}
