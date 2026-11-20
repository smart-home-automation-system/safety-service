package cloud.cholewa.safety.detector;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * What the service knows of one detector, by its registry id: each flag with the moment it took its value (a flag never
 * heard is absent - not known, never a default), the model and firmware of its discovery, when it was last heard, when
 * the snapshot was taken and whether a message of the base confirmed it since the start - a state read back from the
 * database is not.
 */
public record DetectorState(
    long detectorId,
    Map<DetectorFlag, Flag> flags,
    String model,
    String firmware,
    Instant lastHeardAt,
    Instant updatedAt,
    boolean confirmed
) {

    public DetectorState {
        flags = flags.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new EnumMap<>(flags));
    }

    public static DetectorState unknown(final long detectorId, final Instant at) {
        return new DetectorState(detectorId, Map.of(), null, null, null, at, true);
    }

    public Flag flag(final DetectorFlag flag) {
        return flags.get(flag);
    }

    /** The state after a message saying {@code value} of {@code flag} at {@code at}. */
    public DetectorState withFlag(final DetectorFlag flag, final boolean value, final Instant at) {
        final Map<DetectorFlag, Flag> next = flags.isEmpty() ? new EnumMap<>(DetectorFlag.class) : new EnumMap<>(flags);
        next.put(flag, Flag.next(flags.get(flag), value, at));
        return new DetectorState(detectorId, next, model, firmware, at, at, true);
    }

    /** The state after the discovery of the detector; a field it does not carry keeps what was known. */
    public DetectorState withDevice(final String model, final String firmware, final Instant at) {
        return new DetectorState(detectorId, flags,
            model == null ? this.model : model,
            firmware == null ? this.firmware : firmware,
            at, at, true);
    }

    /** Whether the database holds something else than {@code other} - a message that only repeats is no write. */
    public boolean storedDiffers(final DetectorState other) {
        return other == null
            || !flags.equals(other.flags)
            || !Objects.equals(model, other.model)
            || !Objects.equals(firmware, other.firmware);
    }
}
