package cloud.cholewa.safety.detector;

import cloud.cholewa.safety.database.StateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Reads the stored state of the detectors and the bases back before the device listener (phase
 * {@code Integer.MAX_VALUE}) starts - a lifecycle of phase 0, the shape of GarageReadingLoader of gate-service. Read back
 * as not confirmed, never over a state a message set first.
 * <p>
 * <b>Never fatal</b>: without it the API knows nothing until the base speaks; the wait is bounded.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class StateLoader implements SmartLifecycle {

    private static final int PHASE = 0;
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(15);

    private final StateStore store;
    private final SafetyStates states;

    private volatile boolean running;

    @Override
    public void start() {
        //blocking on purpose, bounded: two queries, before the first message of a base
        Mono.when(
                store.detectors().doOnNext(states::restore),
                store.bases().doOnNext(states::restore))
            .timeout(STARTUP_TIMEOUT)
            .doOnError(e -> log.warn("The stored state of the detectors was not read back in {}, the start knows none: {}",
                STARTUP_TIMEOUT, e.toString()))
            .onErrorComplete()
            .block();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
