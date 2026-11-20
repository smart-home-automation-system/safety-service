package cloud.cholewa.safety.database;

import cloud.cholewa.safety.detector.BaseState;
import cloud.cholewa.safety.detector.DetectorState;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;

/**
 * Takes the writes of the state off the path of the device listener: the state in memory is what the API answers, so a
 * message is acknowledged once memory changed, never after the database. A database slow or away would otherwise hold
 * the three messages in flight for the whole bound and retries of a write each, and a real alarm behind them could
 * expire in the queue (10 min TTL). The writes run one after the other, in the order the states changed - each is
 * bounded and its failure a WARN inside {@link StateStore}, so one never stops the next.
 */
@Slf4j
@Component
public class StateWriter {

    private static final Duration EMIT_RETRY = Duration.ofSeconds(1);

    private final StateStore store;
    private final Sinks.Many<Mono<Void>> writes = Sinks.many().unicast().onBackpressureBuffer();
    private final Disposable subscription;

    public StateWriter(final StateStore store) {
        this.store = store;
        this.subscription = writes.asFlux()
            .concatMap(write -> write.onErrorResume(failure -> {
                log.warn("A write of the state failed: {}", failure.toString());
                return Mono.empty();
            }))
            .subscribe();
    }

    public void submit(final DetectorState state) {
        emit(Mono.defer(() -> store.write(state)));
    }

    public void submit(final BaseState state) {
        emit(Mono.defer(() -> store.write(state)));
    }

    //the listener takes three messages at once: a concurrent emission is retried until the sink takes it
    private void emit(final Mono<Void> write) {
        writes.emitNext(write, Sinks.EmitFailureHandler.busyLooping(EMIT_RETRY));
    }

    @PreDestroy
    void stop() {
        subscription.dispose();
    }
}
