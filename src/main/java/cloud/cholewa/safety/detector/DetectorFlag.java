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
    BATTERY_LOW("battery", "Low", Words.NORMAL),
    END_OF_LIFE("lifeend", "EOL", Words.NORMAL),
    FAULT("smokefault", "Fault", Words.NORMAL),
    MUTED("muted", "Muted", Words.NORMAL),
    SELF_TEST_PROBLEM(null, null, null);

    private final String entity;
    private final String on;
    private final String off;

    //the word of every flag that is off but the alarm and online - a holder, an enum constant cannot read a static field
    //of its own enum declared after it
    private static final class Words {
        private static final String NORMAL = "Normal";

        private Words() {
        }
    }

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
