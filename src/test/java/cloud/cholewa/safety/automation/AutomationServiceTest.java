package cloud.cholewa.safety.automation;

import cloud.cholewa.safety.api.model.AutomationReply;
import cloud.cholewa.safety.api.model.ReactionRunReply;
import cloud.cholewa.safety.database.AutomationStore;
import cloud.cholewa.safety.error.SafetyError;
import cloud.cholewa.safety.error.SafetyException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the switches of HAS-468: written whole, three booleans and nothing else; an alarm falls back on the last known, never
//on a guess; the runs of a period in the time of the house
class AutomationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");

    private final AutomationStore store = mock(AutomationStore.class);
    private final AutomationService sut = new AutomationService(store, Clock.fixed(NOW, HOUSE));

    @Test
    void should_write_the_three_switches_and_answer_them() {
        when(store.write(new AutomationSwitches(true, false, true), NOW)).thenReturn(Mono.empty());

        sut.save(Map.of("lightsOn", true, "blindsUp", false, "airconOff", true)).as(StepVerifier::create)
            .expectNext(new AutomationReply(true, false, true))
            .verifyComplete();
    }

    @Test
    void should_refuse_a_body_that_is_not_the_three_switches() {
        final Map<String, Object> withNull = new HashMap<>(Map.<String, Object>of("lightsOn", true, "blindsUp", false));
        withNull.put("airconOff", null);
        for (final Map<String, Object> body : List.of(
            Map.<String, Object>of("lightsOn", true, "blindsUp", false),
            Map.<String, Object>of("lightsOn", true, "blindsUp", false, "airconOff", true, "furnaceOff", true),
            Map.<String, Object>of("lightsOn", "yes", "blindsUp", false, "airconOff", true),
            withNull)) {
            sut.save(body).as(StepVerifier::create)
                .expectErrorSatisfies(failure -> assertThat(failure)
                    .isInstanceOfSatisfying(SafetyException.InvalidAutomation.class,
                        invalid -> assertThat(invalid.getError()).isEqualTo(SafetyError.INVALID_AUTOMATION)))
                .verify();
        }
        sut.save(null).as(StepVerifier::create).expectError(SafetyException.InvalidAutomation.class).verify();
        verify(store, never()).write(any(), any());
    }

    @Test
    void should_name_what_is_wrong() {
        assertThat(AutomationService.problemOf(Map.of("lightsOn", true, "blindsUp", false, "airconOff", true,
            "furnaceOff", true))).contains("Unknown fields [furnaceOff] - the switches are [airconOff, blindsUp, lightsOn]");
        assertThat(AutomationService.problemOf(Map.of("lightsOn", true)))
            .contains("Every switch must be true or false: [airconOff, blindsUp]");
        assertThat(AutomationService.problemOf(Map.of("lightsOn", true, "blindsUp", false, "airconOff", true)))
            .isEmpty();
    }

    @Test
    void should_fall_back_on_the_last_known_switches_when_the_database_is_away() {
        when(store.read()).thenReturn(Mono.just(new AutomationSwitches(true, true, false)));
        sut.automation().block();
        when(store.read()).thenReturn(Mono.error(new IllegalStateException("database away")));

        sut.forAlarm().as(StepVerifier::create)
            .expectNext(new AutomationSwitches(true, true, false))
            .verifyComplete();
    }

    @Test
    void should_know_no_switches_when_none_was_ever_read() {
        when(store.read()).thenReturn(Mono.error(new IllegalStateException("database away")));

        sut.forAlarm().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_wait_for_the_database_at_most_read_within() {
        when(store.read()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(sut::forAlarm)
            .thenAwait(AutomationService.READ_WITHIN)
            .verifyComplete();
    }

    @Test
    void should_answer_the_runs_of_a_period_in_the_time_of_the_house() {
        final LocalDateTime from = LocalDateTime.of(2026, 11, 22, 0, 0);
        final LocalDateTime to = LocalDateTime.of(2026, 11, 23, 0, 0);
        when(store.runs(from.atZone(HOUSE).toInstant(), to.atZone(HOUSE).toInstant())).thenReturn(Flux.just(
            new AutomationStore.RunRow(7L, "lightsOn", "kitchen", NOW, "sent")));

        sut.runs(from, to).as(StepVerifier::create)
            .expectNext(new ReactionRunReply(7L, "lightsOn", "kitchen", LocalDateTime.of(2026, 11, 22, 11, 0), "sent"))
            .verifyComplete();
    }

    @Test
    void should_refuse_a_period_longer_than_thirty_days() {
        sut.runs(LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 11, 22, 0, 0)).as(StepVerifier::create)
            .expectError(SafetyException.InvalidRange.class)
            .verify();
    }
}
