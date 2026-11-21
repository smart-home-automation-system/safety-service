package cloud.cholewa.safety.command;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

//the commands of one detector in memory (HAS-464): no judgement while one waits, confirmed by an answer of its kind
//taken after its sending, unconfirmed at the end of its window, the run of unconfirmed ones ended by a confirmed one
class CommandWatchTest {

    private static final Instant SENT = Instant.parse("2026-11-20T10:00:00Z");
    private static final CommandWatch.Pending MUTE = new CommandWatch.Pending("sh-1", DetectorCommand.MUTE, SENT);

    @Test
    void should_judge_nothing_while_a_command_waits() {
        final CommandWatch waiting = CommandWatch.NONE.issued(MUTE);

        assertThat(waiting.pending()).isEqualTo(MUTE);
        assertThat(waiting.lastConfirmed()).isNull();
    }

    @Test
    void should_confirm_only_by_an_answer_of_its_kind_taken_after_its_sending() {
        final CommandWatch waiting = CommandWatch.NONE.issued(MUTE);

        assertThat(waiting.reported(DetectorCommand.SELF_TEST, SENT.plusSeconds(5))).isEmpty();
        assertThat(waiting.reported(DetectorCommand.MUTE, SENT.minusSeconds(1))).isEmpty();
        assertThat(waiting.reported(DetectorCommand.MUTE, SENT.plusSeconds(5))).hasValueSatisfying(after -> {
            assertThat(after.pending()).isNull();
            assertThat(after.lastConfirmed()).isTrue();
        });
        assertThat(CommandWatch.NONE.reported(DetectorCommand.MUTE, SENT)).isEmpty();
    }

    @Test
    void should_count_the_unconfirmed_in_a_row_until_one_is_confirmed() {
        final CommandWatch first = CommandWatch.NONE.issued(MUTE).expired("sh-1").orElseThrow();
        final CommandWatch second = first.issued(new CommandWatch.Pending("sh-2", DetectorCommand.SELF_TEST, SENT))
            .expired("sh-2").orElseThrow().withAnnounced();

        assertThat(first.lastConfirmed()).isFalse();
        assertThat(first.inARow()).isEqualTo(1);
        assertThat(second.inARow()).isEqualTo(2);
        assertThat(second.announced()).isTrue();
        assertThat(second.issued(MUTE).reported(DetectorCommand.MUTE, SENT).orElseThrow())
            .isEqualTo(new CommandWatch(null, true, 0, false));
    }

    //the window of a command confirmed or replaced ends with nothing
    @Test
    void should_expire_only_the_command_still_pending() {
        final CommandWatch replaced = CommandWatch.NONE.issued(MUTE)
            .issued(new CommandWatch.Pending("sh-2", DetectorCommand.MUTE, SENT.plusSeconds(70)));

        assertThat(replaced.expired("sh-1")).isEmpty();
        assertThat(CommandWatch.NONE.expired("sh-1")).isEmpty();
    }
}
