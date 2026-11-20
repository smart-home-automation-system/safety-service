package cloud.cholewa.safety.detector;

import java.time.Instant;

/** A yes / no of a detector or a base and the moment the service learnt it took that value. */
public record Flag(boolean value, Instant changedAt) {

    /** The flag after a message saying {@code value} at {@code at}: the same flag when it says nothing new. */
    Flag next(final boolean value, final Instant at) {
        return this.value == value ? this : new Flag(value, at);
    }

    static Flag next(final Flag flag, final boolean value, final Instant at) {
        return flag == null ? new Flag(value, at) : flag.next(value, at);
    }
}
