package cloud.cholewa.safety.detector;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;

/**
 * The state of every detector (by registry id) and every base (by serial), in memory - what the API answers. A message
 * changes one entry atomically (the listener takes three at once); the change answers the state to store when the
 * database would hold something else, so a message that only repeats a value writes nothing. A state read back at the
 * start never replaces one a message already set.
 */
@Component
public class SafetyStates {

    private final Map<Long, DetectorState> detectors = new ConcurrentHashMap<>();
    private final Map<String, BaseState> bases = new ConcurrentHashMap<>();

    public Optional<DetectorState> detector(final long detectorId) {
        return Optional.ofNullable(detectors.get(detectorId));
    }

    public Optional<BaseState> base(final String stationSerial) {
        return Optional.ofNullable(bases.get(stationSerial));
    }

    public Collection<BaseState> bases() {
        return bases.values();
    }

    /**
     * Applies {@code change} to the state of a detector, a state not known before starting from {@code unknown}.
     *
     * @return the state to store, empty when the database holds it already
     */
    public Optional<DetectorState> changeDetector(
        final long detectorId,
        final LongFunction<DetectorState> unknown,
        final UnaryOperator<DetectorState> change
    ) {
        final AtomicReference<DetectorState> before = new AtomicReference<>();
        final DetectorState after = detectors.compute(detectorId, (id, old) -> {
            before.set(old);
            return change.apply(old == null ? unknown.apply(id) : old);
        });
        return after.storedDiffers(before.get()) ? Optional.of(after) : Optional.empty();
    }

    /** As {@link #changeDetector} for a base. */
    public Optional<BaseState> changeBase(
        final String stationSerial,
        final Function<String, BaseState> unknown,
        final UnaryOperator<BaseState> change
    ) {
        final AtomicReference<BaseState> before = new AtomicReference<>();
        final BaseState after = bases.compute(stationSerial, (serial, old) -> {
            before.set(old);
            return change.apply(old == null ? unknown.apply(serial) : old);
        });
        return after.storedDiffers(before.get()) ? Optional.of(after) : Optional.empty();
    }

    /** A state read back from the database, not confirmed; kept only when no message set one first. */
    public void restore(final DetectorState state) {
        detectors.putIfAbsent(state.detectorId(), state);
    }

    public void restore(final BaseState state) {
        bases.putIfAbsent(state.stationSerial(), state);
    }
}
