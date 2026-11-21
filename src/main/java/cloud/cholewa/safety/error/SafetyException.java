package cloud.cholewa.safety.error;

import lombok.Getter;

//A request the API refuses, by its cause; one subclass per cause, so each has its own DomainExceptionProcessor of
//cholewa-commons with its status (ExceptionHandlerConfig). The message is answered as details for a 4xx
@Getter
public abstract class SafetyException extends RuntimeException {

    private final SafetyError error;

    protected SafetyException(final SafetyError error, final String message) {
        super(message);
        this.error = error;
    }

    public static final class RegistryUnavailable extends SafetyException {
        public RegistryUnavailable() {
            super(SafetyError.REGISTRY_UNAVAILABLE, "The detectors of the device registry have not been read yet");
        }
    }

    public static final class RoomNotFound extends SafetyException {
        public RoomNotFound(final String room) {
            super(SafetyError.NOT_FOUND_ROOM, "No fire detector in the room " + room);
        }
    }

    public static final class InvalidRange extends SafetyException {
        public InvalidRange(final String reason) {
            super(SafetyError.INVALID_RANGE, reason);
        }
    }

    public static final class CommandTooSoon extends SafetyException {
        public CommandTooSoon(final long id) {
            super(SafetyError.COMMAND_TOO_SOON, "A command of the detector " + id + " was sent less than a minute ago");
        }
    }

    public static final class CommandNotSent extends SafetyException {
        public CommandNotSent(final long id) {
            super(SafetyError.COMMAND_NOT_SENT, "The command of the detector " + id + " could not be sent");
        }
    }

    public static final class NothingToMute extends SafetyException {
        public NothingToMute(final long id) {
            super(SafetyError.NOTHING_TO_MUTE, "The detector " + id + " is not in alarm - there is nothing to mute");
        }
    }

    public static final class DetectorNotFound extends SafetyException {
        public DetectorNotFound(final long id) {
            super(SafetyError.NOT_FOUND_DETECTOR, "No fire detector of the id " + id);
        }
    }
}
