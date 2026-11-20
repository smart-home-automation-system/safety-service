package cloud.cholewa.safety.error;

import cloud.cholewa.commons.error.model.ErrorId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

//The causes of a request the API refuses. Their names are the codes of the answer (ErrorId.codeOf) - wire contract for
//the dashboard, pinned by SafetyErrorTest
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum SafetyError implements ErrorId {

    //the detectors of the registry were never read since the start
    REGISTRY_UNAVAILABLE("Registry unavailable"),

    //no room of that name, or no detector in it
    NOT_FOUND_ROOM("Room not found"),

    //no detector of that id: not in the registry, switched off, or not a fire detector
    NOT_FOUND_DETECTOR("Detector not found"),

    //the period of the fire alarms is not one: from not before to, or longer than 30 days (HAS-461)
    INVALID_RANGE("Invalid range");

    private final String description;
}
