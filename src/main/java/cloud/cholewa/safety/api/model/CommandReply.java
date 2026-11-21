package cloud.cholewa.safety.api.model;

import java.time.LocalDateTime;

/**
 * A command sent to the base (HAS-464), answered with 202: the detector, the command ({@code mute}, {@code self test}),
 * who sent it, the request id of its payload and when it was sent (the time of the house). Whether the base did it is
 * told by the reads - {@code lastCommandConfirmed} of the detector.
 */
public record CommandReply(long detectorId, String command, String source, String requestId, LocalDateTime sentAt) {
}
