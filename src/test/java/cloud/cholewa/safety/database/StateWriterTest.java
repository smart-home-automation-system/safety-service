package cloud.cholewa.safety.database;

import cloud.cholewa.safety.detector.BaseState;
import cloud.cholewa.safety.detector.DetectorFlag;
import cloud.cholewa.safety.detector.DetectorState;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StateWriterTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private final StateStore store = mock(StateStore.class);

    //the listener never waits for the database: a submit returns while the write before still runs, and the writes run
    //one after the other in the order submitted
    @Test
    void should_write_in_order_without_holding_the_caller() {
        final Sinks.Empty<Void> slowWrite = Sinks.empty();
        final List<Long> written = new ArrayList<>();
        when(store.write(any(DetectorState.class))).thenAnswer(call -> {
            final DetectorState state = call.getArgument(0);
            written.add(state.detectorId());
            return state.detectorId() == 150L ? slowWrite.asMono() : Mono.empty();
        });
        final StateWriter writer = new StateWriter(store);

        writer.submit(state(150L));
        writer.submit(state(151L));

        //the first write still runs: the second waits for it, the caller did not
        assertThat(written).containsExactly(150L);
        slowWrite.tryEmitEmpty();
        assertThat(written).containsExactly(150L, 151L);
    }

    //a write that fails beyond StateStore never stops the writes after it
    @Test
    void should_go_on_after_a_failed_write() {
        when(store.write(any(BaseState.class))).thenReturn(Mono.error(new IllegalStateException("broken")));
        when(store.write(any(DetectorState.class))).thenReturn(Mono.empty());
        final StateWriter writer = new StateWriter(store);

        writer.submit(BaseState.unknown("SBS50ABCDEF01", NOW).withOnline(true, NOW));
        writer.submit(state(150L));

        verify(store).write(any(DetectorState.class));
    }

    private static DetectorState state(final long id) {
        return DetectorState.unknown(id, NOW).withFlag(DetectorFlag.ALARM, false, NOW);
    }
}
