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

    public static final class DetectorNotFound extends SafetyException {
        public DetectorNotFound(final long id) {
            super(SafetyError.NOT_FOUND_DETECTOR, "No fire detector of the id " + id);
        }
    }
}
