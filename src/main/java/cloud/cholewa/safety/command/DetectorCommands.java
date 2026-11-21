package cloud.cholewa.safety.command;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The commands of every detector in memory (HAS-464): the {@link CommandWatch} of each, changed atomically per detector,
 * and the moment of its last command - one command per detector in {@link #ONE_COMMAND_EVERY}, a mute and a self test
 * alike. Lost with a restart: a command sent before it is never confirmed, and the next may be sent at once.
 */
@Component
public class DetectorCommands {

    static final Duration ONE_COMMAND_EVERY = Duration.ofMinutes(1);

    private final Map<Long, CommandWatch> watches = new ConcurrentHashMap<>();
    private final Map<Long, Instant> lastSent = new ConcurrentHashMap<>();

    /** Takes the minute of the detector for a command sent now; {@code false} when one was sent within it. */
    boolean reserve(final long detectorId, final Instant now) {
        final AtomicBoolean reserved = new AtomicBoolean();
        lastSent.compute(detectorId, (id, last) -> {
            if (last != null && now.isBefore(last.plus(ONE_COMMAND_EVERY))) {
                return last;
            }
            reserved.set(true);
            return now;
        });
        return reserved.get();
    }

    /** Gives the minute back - the command could not be sent; a newer reservation is kept. */
    void release(final long detectorId, final Instant reservedAt) {
        lastSent.remove(detectorId, reservedAt);
    }

    void issued(final long detectorId, final CommandWatch.Pending command) {
        watches.compute(detectorId, (id, watch) -> (watch == null ? CommandWatch.NONE : watch).issued(command));
    }

    /** The pending command this answer of the base confirmed, empty when it confirmed none. */
    Optional<CommandWatch.Pending> reported(final long detectorId, final DetectorCommand command, final Instant at) {
        final AtomicReference<CommandWatch.Pending> confirmed = new AtomicReference<>();
        watches.computeIfPresent(detectorId, (id, watch) -> watch.reported(command, at)
            .map(after -> {
                confirmed.set(watch.pending());
                return after;
            })
            .orElse(watch));
        return Optional.ofNullable(confirmed.get());
    }

    /** The watch after the window of the command, when it was still pending; empty when it was confirmed or replaced. */
    Optional<CommandWatch> expired(final long detectorId, final String requestId) {
        final AtomicReference<CommandWatch> expired = new AtomicReference<>();
        watches.computeIfPresent(detectorId, (id, watch) -> watch.expired(requestId)
            .map(after -> {
                expired.set(after);
                return after;
            })
            .orElse(watch));
        return Optional.ofNullable(expired.get());
    }

    void announced(final long detectorId) {
        watches.computeIfPresent(detectorId, (id, watch) -> watch.withAnnounced());
    }

    /** Whether the base answered the last command of the detector; empty with none or while one waits. */
    public Optional<Boolean> lastConfirmed(final long detectorId) {
        return Optional.ofNullable(watches.get(detectorId)).map(CommandWatch::lastConfirmed);
    }
}
