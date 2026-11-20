package cloud.cholewa.safety.detector;

import cloud.cholewa.safety.database.StateStore;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StateLoaderTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private final StateStore store = mock(StateStore.class);
    private final SafetyStates states = new SafetyStates();
    private final StateLoader loader = new StateLoader(store, states);

    @Test
    void should_read_the_stored_states_back_not_confirmed() {
        when(store.detectors()).thenReturn(Flux.just(new DetectorState(150L,
            Map.of(DetectorFlag.ALARM, new Flag(false, NOW)), "XS01-M", "v1.8.0", NOW, NOW, false)));
        when(store.bases()).thenReturn(Flux.just(new BaseState("SBS50ABCDEF01", new Flag(true, NOW), "192.0.2.82", "iot",
            -35, NOW, NOW, false)));

        loader.start();

        assertThat(loader.isRunning()).isTrue();
        assertThat(states.detector(150L)).hasValueSatisfying(state -> assertThat(state.confirmed()).isFalse());
        assertThat(states.base("SBS50ABCDEF01")).isPresent();
    }

    //never fatal: without the rows the API knows nothing until the base speaks
    @Test
    void should_start_when_the_database_cannot_be_read() {
        when(store.detectors()).thenReturn(Flux.error(new IllegalStateException("database away")));
        when(store.bases()).thenReturn(Flux.empty());

        loader.start();

        assertThat(loader.isRunning()).isTrue();
        assertThat(states.detector(150L)).isEmpty();
    }
}
