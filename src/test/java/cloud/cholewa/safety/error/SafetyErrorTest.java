package cloud.cholewa.safety.error;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

//the names are the codes of the answer - wire contract for the dashboard; a renamed one needs a text there
class SafetyErrorTest {

    @Test
    void should_keep_the_codes_the_dashboard_reads() {
        assertThat(Arrays.stream(SafetyError.values()).map(Enum::name))
            .containsExactly("REGISTRY_UNAVAILABLE", "NOT_FOUND_ROOM", "NOT_FOUND_DETECTOR", "INVALID_RANGE",
                "COMMAND_TOO_SOON", "COMMAND_NOT_SENT");
    }
}
