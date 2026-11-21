package cloud.cholewa.safety.command;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

//one command per detector a minute, a minute given back only by the command that took it; the last judgement per
//detector
class DetectorCommandsTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private final DetectorCommands commands = new DetectorCommands();

    @Test
    void should_take_one_command_per_detector_a_minute() {
        assertThat(commands.reserve(150L, NOW)).isTrue();
        assertThat(commands.reserve(150L, NOW.plusSeconds(59))).isFalse();
        assertThat(commands.reserve(151L, NOW.plusSeconds(1))).isTrue();
        assertThat(commands.reserve(150L, NOW.plusSeconds(60))).isTrue();
    }

    @Test
    void should_give_the_minute_back_only_to_the_command_that_took_it() {
        commands.reserve(150L, NOW);
        commands.release(150L, NOW.minusSeconds(1));
        assertThat(commands.reserve(150L, NOW.plusSeconds(1))).isFalse();

        commands.release(150L, NOW);
        assertThat(commands.reserve(150L, NOW.plusSeconds(1))).isTrue();
    }

    @Test
    void should_tell_the_last_judgement_of_a_detector() {
        final CommandWatch.Pending mute = new CommandWatch.Pending("sh-1", DetectorCommand.MUTE, NOW);

        assertThat(commands.lastConfirmed(150L)).isEmpty();
        commands.issued(150L, mute);
        assertThat(commands.lastConfirmed(150L)).isEmpty();
        assertThat(commands.reported(150L, DetectorCommand.MUTE, NOW.plusSeconds(3))).contains(mute);
        assertThat(commands.lastConfirmed(150L)).contains(true);
        assertThat(commands.reported(150L, DetectorCommand.MUTE, NOW.plusSeconds(4))).isEmpty();
    }
}
