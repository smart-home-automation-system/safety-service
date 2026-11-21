package cloud.cholewa.safety.command;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * Who sent a command of a detector (HAS-464), the {@code source} of the request: the dashboard (the default), or a call
 * made by hand (Bruno). Its word is stored with the command.
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum CommandSource {

    DASHBOARD("dashboard"),
    MANUAL("manual");

    private final String value;

    public static Optional<CommandSource> fromValue(final String value) {
        return Arrays.stream(values()).filter(source -> source.value.equals(value)).findFirst();
    }
}
