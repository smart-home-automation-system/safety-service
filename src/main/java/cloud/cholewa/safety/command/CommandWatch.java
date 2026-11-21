package cloud.cholewa.safety.command;

import java.time.Instant;
import java.util.Optional;

/**
 * What is known of the commands of one detector, in memory (HAS-464, the rule of water-service HAS-321 and
 * blinds-service HAS-250), without I/O - pinned by {@code CommandWatchTest}.
 * <p>
 * {@code pending} - the command waiting for the answer of the base, null when none; a newer command takes its place, the
 * older is neither confirmed nor counted. {@code lastConfirmed} - null with no command since the start and while one
 * waits, false once none answered it within its window, true once one did. {@code inARow} - the unconfirmed commands in
 * a row, ended by a confirmed one. {@code announced} - the alert of this run of unconfirmed commands got through.
 */
public record CommandWatch(Pending pending, Boolean lastConfirmed, int inARow, boolean announced) {

    static final CommandWatch NONE = new CommandWatch(null, null, 0, false);

    /** A command sent: the request id of its payload, the command and when it was sent. */
    public record Pending(String requestId, DetectorCommand command, Instant sentAt) {
    }

    CommandWatch issued(final Pending command) {
        return new CommandWatch(command, null, inARow, announced);
    }

    //an answer of the base: the pending command of that kind is confirmed by one taken at or after its sending
    Optional<CommandWatch> reported(final DetectorCommand command, final Instant at) {
        return Optional.ofNullable(pending)
            .filter(waiting -> waiting.command() == command && !at.isBefore(waiting.sentAt()))
            .map(waiting -> new CommandWatch(null, true, 0, false));
    }

    //the window of a command is over: unconfirmed when it is still pending, nothing for one confirmed or replaced
    Optional<CommandWatch> expired(final String requestId) {
        return Optional.ofNullable(pending)
            .filter(waiting -> waiting.requestId().equals(requestId))
            .map(waiting -> new CommandWatch(null, false, inARow + 1, announced));
    }

    CommandWatch withAnnounced() {
        return new CommandWatch(pending, lastConfirmed, inARow, true);
    }
}
