package cloud.cholewa.safety.command;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The commands of a detector (HAS-464): {@code value} - the word stored and answered by the API; {@code cmd} - the word
 * of the payload the base announces in the configs of its buttons ({@code mute}, {@code self_test}).
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum DetectorCommand {

    MUTE("mute", "mute"),
    SELF_TEST("self test", "self_test");

    private final String value;
    private final String cmd;
}
