package cloud.cholewa.safety.monitor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the technical monitor makes of what it watches and the alerts already written (HAS-463) - pure, pinned by
 * AlertEvaluatorTest, by the rules of the availability monitor of gate-service (HAS-453). A thing is out once it has been
 * out for its own bound. Out and not told - no row, or a row whose alert never got through - it is an alert; told and
 * still out, a reminder every reminderEvery; out before (told or not) and fine now, an info - never a thing only not known
 * (after a restart, a flag the base never reported), which is not back until it is known. A row of a thing no longer
 * watched (a detector left the registry) is closed without a word.
 */
public final class AlertEvaluator {

    private AlertEvaluator() {
    }

    /** A watched thing: since when it is out (null while it is fine or not known), whether its state is known. */
    public record Watched(AlertKind kind, String device, String name, Instant since, boolean known, Duration after) {

        public String item() {
            return kind.itemOf(device);
        }
    }

    /** An alert written: {@code told} once the broker took it, {@code lastAlertAt} the alert or the last reminder told. */
    public record Alert(AlertKind kind, String device, String name, Instant since, boolean told, Instant lastAlertAt) {

        public String item() {
            return kind.itemOf(device);
        }
    }

    /** What is due: alerts to tell, reminders, returns, and the alerts of things no longer watched. */
    public record Decision(List<Alert> alerts, List<Alert> reminders, List<Alert> returns, List<Alert> forgotten) {
    }

    public static Decision decide(final List<Watched> watched, final Map<String, Alert> open, final Instant now,
                                  final Duration reminderEvery) {
        final List<Alert> alerts = watched.stream()
            .filter(thing -> out(thing, now))
            .filter(thing -> Optional.ofNullable(open.get(thing.item())).map(alert -> !alert.told()).orElse(true))
            .map(thing -> Optional.ofNullable(open.get(thing.item()))
                .orElseGet(() -> new Alert(thing.kind(), thing.device(), thing.name(), thing.since(), false, null)))
            .toList();
        final List<Alert> reminders = watched.stream()
            .filter(thing -> out(thing, now))
            .flatMap(thing -> Optional.ofNullable(open.get(thing.item())).stream())
            .filter(Alert::told)
            .filter(alert -> alert.lastAlertAt() == null || !alert.lastAlertAt().plus(reminderEvery).isAfter(now))
            .toList();
        final List<Alert> returns = watched.stream()
            .filter(thing -> thing.known() && thing.since() == null)
            .flatMap(thing -> Optional.ofNullable(open.get(thing.item())).stream())
            .toList();
        final Set<String> items = watched.stream().map(Watched::item).collect(Collectors.toSet());
        final List<Alert> forgotten = open.values().stream().filter(alert -> !items.contains(alert.item())).toList();
        return new Decision(alerts, reminders, returns, forgotten);
    }

    private static boolean out(final Watched thing, final Instant now) {
        return thing.since() != null && !thing.since().plus(thing.after()).isAfter(now);
    }
}
