package cloud.cholewa.safety.monitor;

import cloud.cholewa.safety.monitor.AlertEvaluator.Alert;
import cloud.cholewa.safety.monitor.AlertEvaluator.Decision;
import cloud.cholewa.safety.monitor.AlertEvaluator.Watched;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//the rules of the technical monitor (HAS-463), the rules of the availability monitor of gate-service
class AlertEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-11-20T18:00:00Z");
    private static final Duration HOUR = Duration.ofHours(1);

    @Test
    void should_alert_a_thing_out_and_not_told() {
        final Watched battery = watched(AlertKind.BATTERY_LOW, NOW.minusSeconds(60), true);

        final Decision decision = AlertEvaluator.decide(List.of(battery), Map.of(), NOW, HOUR);

        assertThat(decision.alerts()).extracting(Alert::item).containsExactly("battery:151");
        assertThat(decision.reminders()).isEmpty();
        assertThat(decision.returns()).isEmpty();
    }

    //a row whose alert never got through is an alert again, never a reminder
    @Test
    void should_alert_again_a_row_not_told() {
        final Watched battery = watched(AlertKind.BATTERY_LOW, NOW.minusSeconds(600), true);
        final Alert untold = alert(AlertKind.BATTERY_LOW, NOW.minusSeconds(600), false, null);

        final Decision decision = AlertEvaluator.decide(List.of(battery), Map.of(untold.item(), untold), NOW, HOUR);

        assertThat(decision.alerts()).containsExactly(untold);
        assertThat(decision.reminders()).isEmpty();
    }

    @Test
    void should_remind_every_hour() {
        final Watched fault = watched(AlertKind.FAULT, NOW.minus(Duration.ofHours(3)), true);
        final Alert recent = alert(AlertKind.FAULT, NOW.minus(Duration.ofHours(3)), true, NOW.minusSeconds(1800));
        final Alert due = alert(AlertKind.FAULT, NOW.minus(Duration.ofHours(3)), true, NOW.minus(HOUR));

        assertThat(AlertEvaluator.decide(List.of(fault), Map.of(recent.item(), recent), NOW, HOUR).reminders()).isEmpty();
        assertThat(AlertEvaluator.decide(List.of(fault), Map.of(due.item(), due), NOW, HOUR).reminders())
            .containsExactly(due);
    }

    //back once known and fine; a thing only not known (after a restart, a flag never reported) is not back
    @Test
    void should_return_only_what_is_known_and_fine() {
        final Alert open = alert(AlertKind.OFFLINE, NOW.minus(HOUR), true, NOW.minus(HOUR));

        assertThat(AlertEvaluator.decide(List.of(watched(AlertKind.OFFLINE, null, true)), Map.of(open.item(), open), NOW,
            HOUR).returns()).containsExactly(open);
        assertThat(AlertEvaluator.decide(List.of(watched(AlertKind.OFFLINE, null, false)), Map.of(open.item(), open), NOW,
            HOUR).returns()).isEmpty();
    }

    @Test
    void should_wait_for_the_bound_of_a_thing() {
        final Watched slow = new Watched(AlertKind.BASE_OFFLINE, "SBS50ABCDEF01", "the base", NOW.minusSeconds(60), true,
            Duration.ofMinutes(5));

        assertThat(AlertEvaluator.decide(List.of(slow), Map.of(), NOW, HOUR).alerts()).isEmpty();
        assertThat(AlertEvaluator.decide(List.of(slow), Map.of(), NOW.plusSeconds(240), HOUR).alerts()).hasSize(1);
    }

    //a detector that left the registry: its row is closed without a word
    @Test
    void should_forget_a_thing_no_longer_watched() {
        final Alert gone = alert(AlertKind.END_OF_LIFE, NOW.minus(HOUR), true, NOW.minus(HOUR));

        final Decision decision = AlertEvaluator.decide(List.of(), Map.of(gone.item(), gone), NOW, HOUR);

        assertThat(decision.forgotten()).containsExactly(gone);
        assertThat(decision.returns()).isEmpty();
    }

    private static Watched watched(final AlertKind kind, final Instant since, final boolean known) {
        return new Watched(kind, "151", "the detector Smoke detector in kitchen (151)", since, known, Duration.ZERO);
    }

    private static Alert alert(final AlertKind kind, final Instant since, final boolean told, final Instant lastAlertAt) {
        return new Alert(kind, "151", "the detector Smoke detector in kitchen (151)", since, told, lastAlertAt);
    }
}
