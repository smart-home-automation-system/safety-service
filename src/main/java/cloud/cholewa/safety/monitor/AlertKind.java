package cloud.cholewa.safety.monitor;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

//What the technical monitor tells (HAS-463): the stored value is the word of the column kind, the problem the words of
//the message. A detector problem is the state its base reports, a base problem its availability or its silence
@Getter
@RequiredArgsConstructor
public enum AlertKind {

    OFFLINE(Words.OFFLINE, Words.OFFLINE, true),
    BATTERY_LOW("battery", "low on battery", true),
    END_OF_LIFE("end of life", "at its end of life - replace it", true),
    FAULT("fault", "faulty", true),
    BASE_OFFLINE("base offline", Words.OFFLINE, false),
    BASE_SILENT("base silent", "silent - nothing heard", false);

    private final String value;
    private final String problem;
    private final boolean detector;

    public String itemOf(final String device) {
        return value + ":" + device;
    }

    public static Optional<AlertKind> of(final String value) {
        return Arrays.stream(values()).filter(kind -> kind.value.equals(value)).findFirst();
    }

    //the word of a detector and a base offline - a holder, an enum constant cannot read a static field of its own enum
    //declared after it
    private static final class Words {
        private static final String OFFLINE = "offline";

        private Words() {
        }
    }
}
