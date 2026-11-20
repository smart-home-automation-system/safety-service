package cloud.cholewa.safety.detector;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * What the service knows of a detector as yes / no, and how its base says it: the entity of its topic and the two words
 * of {@code {"status": "<word>"}} - the {@code payload_on} / {@code payload_off} of its discovery (XS01-M, firmware
 * v1.8.0, seen 2026-11-16). The self test comes on a topic of its own as {@code {"problem": <boolean>}}, so it has no
 * entity here. A CO or heat detector names its alarm otherwise - not seen yet, its entity is dropped until it is.
 */
@Getter
@RequiredArgsConstructor
public enum DetectorFlag {

    ALARM("smokealarm", "Detected", "Cleared"),
    ONLINE("online", "Online", "Offline"),
    BATTERY_LOW("battery", "Low", "Normal"),
    END_OF_LIFE("lifeend", "EOL", "Normal"),
    FAULT("smokefault", "Fault", "Normal"),
    MUTED("muted", "Muted", "Normal"),
    SELF_TEST_PROBLEM(null, null, null);

    private final String entity;
    private final String on;
    private final String off;

    public static Optional<DetectorFlag> ofEntity(final String entity) {
        return Arrays.stream(values())
            .filter(flag -> flag.entity != null && flag.entity.equals(entity))
            .findFirst();
    }

    /** The value a word of the base says; empty for a word that is neither of the two. */
    public Optional<Boolean> valueOfWord(final String word) {
        if (on != null && on.equals(word)) {
            return Optional.of(true);
        }
        if (off != null && off.equals(word)) {
            return Optional.of(false);
        }
        return Optional.empty();
    }
}
