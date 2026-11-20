package cloud.cholewa.safety.monitor;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.safety.config.SafetyProperties;
import cloud.cholewa.safety.detector.BaseState;
import cloud.cholewa.safety.detector.DetectorFlag;
import cloud.cholewa.safety.detector.DetectorState;
import cloud.cholewa.safety.detector.Flag;
import cloud.cholewa.safety.detector.SafetyStates;
import cloud.cholewa.safety.monitor.AlertEvaluator.Alert;
import cloud.cholewa.safety.monitor.AlertEvaluator.Decision;
import cloud.cholewa.safety.monitor.AlertEvaluator.Watched;
import cloud.cholewa.safety.registry.Detector;
import cloud.cholewa.safety.registry.DetectorRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The technical monitor of the detectors and the base (HAS-463): every minute what the bases reported is judged by
 * {@link AlertEvaluator} against the open episodes of {@code safety_alert} - a detector offline, low on battery, at its end
 * of life or faulty (each the state its base reports, at once), a base offline (its availability - its own word or the
 * last will the broker publishes for it) or silent (nothing from it nor from any of its detectors for
 * {@code safety.base.silent-after}; the base answers the resync every 10 min). What is due goes to the technical channel
 * through NotificationPublisher: one return, one alert and one reminder a pass at most, each naming everything - never the
 * household category, never an SMS (the household hears a fire, not a battery). A row is written when the alert is due and
 * marked told once the broker took it, so an alert that never got through is tried again with the next pass and announced
 * with the return all the same; a restart repeats no alert. A publish neither fails nor stalls the pass for longer than
 * {@link #PUBLISH_TIMEOUT}. No pass before the registry is known - every detector would look removed and its alert closed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TechnicalMonitor {

    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(15);
    //below the delay of a minute: the read of the open episodes and three publishes of 15 s at most
    static final Duration LIMIT = Duration.ofSeconds(55);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DetectorRegistry registry;
    private final SafetyStates states;
    private final AlertStore alertStore;
    private final NotificationPublisher notificationPublisher;
    private final SafetyProperties properties;
    private final Clock clock;
    //the start of the service: the silence of a base is counted from here at the earliest - a state read back holds the
    //last heard of its last write, which may be days old for a quiet base (review of HAS-463)
    private volatile Instant startedAt;

    @PostConstruct
    void markStart() {
        startedAt = clock.instant();
    }

    //two minutes in: the registry read and the bases asked to republish, their answer arrived
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1M")
    Mono<Void> check() {
        return ScheduledPass.bounded("technical monitor of the fire detectors", LIMIT, this::checkOnce);
    }

    Mono<Void> checkOnce() {
        return Mono.defer(() -> registry.isKnown() ? judge(clock.instant()) : Mono.<Void>empty())
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.error("The technical check of the fire detectors "
                + "failed: {}", failure.toString())));
    }

    private Mono<Void> judge(final Instant now) {
        return alertStore.open()
            .collectMap(Alert::item, Function.identity())
            .flatMap(open -> act(AlertEvaluator.decide(watched(now), open, now,
                properties.monitor().reminderEvery()), now));
    }

    /** Every detector of the registry with its four problems, every base of them with its two. */
    List<Watched> watched(final Instant now) {
        final List<Detector> detectors = registry.all();
        final List<Watched> watched = new ArrayList<>();
        detectors.forEach(detector -> {
            final Optional<DetectorState> state = states.detector(detector.id());
            final String device = String.valueOf(detector.id());
            final String name = nameOf(detector);
            watched.add(flagged(AlertKind.OFFLINE, device, name, state, DetectorFlag.ONLINE, false));
            watched.add(flagged(AlertKind.BATTERY_LOW, device, name, state, DetectorFlag.BATTERY_LOW, true));
            watched.add(flagged(AlertKind.END_OF_LIFE, device, name, state, DetectorFlag.END_OF_LIFE, true));
            watched.add(flagged(AlertKind.FAULT, device, name, state, DetectorFlag.FAULT, true));
        });
        stationsOf(detectors).forEach(serial -> {
            final String name = "the base station " + serial;
            final Optional<BaseState> base = states.base(serial);
            final Flag online = base.map(BaseState::online).orElse(null);
            final Instant lastHeard = lastHeard(serial, base, detectors);
            //offline while its last word is offline and nothing came from it since - a base that reconnects need not say
            //online again, what it sends after the last will is enough (review of HAS-463)
            final boolean offline = online != null && !online.value()
                && (lastHeard == null || !lastHeard.isAfter(online.changedAt()));
            watched.add(new Watched(AlertKind.BASE_OFFLINE, serial, name, offline ? online.changedAt() : null,
                online != null, Duration.ZERO));
            //the silence of a base known offline is not told apart - one cause, one alert; not known while it is offline
            final Instant counted = floorAtStart(lastHeard);
            watched.add(new Watched(AlertKind.BASE_SILENT, serial, name,
                !offline && counted != null && !counted.plus(properties.base().silentAfter()).isAfter(now) ? counted : null,
                !offline && counted != null, Duration.ZERO));
        });
        return watched;
    }

    //a problem is out while its flag says so, since the flag took that value; not known while the base never reported it
    private static Watched flagged(final AlertKind kind, final String device, final String name,
                                   final Optional<DetectorState> state, final DetectorFlag flag, final boolean bad) {
        final Flag value = state.map(known -> known.flag(flag)).orElse(null);
        return new Watched(kind, device, name, value != null && value.value() == bad ? value.changedAt() : null,
            value != null, Duration.ZERO);
    }

    private static String nameOf(final Detector detector) {
        final String room = detector.room() == null ? "" : " in " + detector.room().getValue();
        return "the detector " + detector.name() + room + " (" + detector.id() + ")";
    }

    private static TreeSet<String> stationsOf(final List<Detector> detectors) {
        return detectors.stream().map(Detector::stationSerial).filter(Objects::nonNull)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    //never before the start of the service; a base not heard at all since is counted from the start too
    private Instant floorAtStart(final Instant lastHeard) {
        if (startedAt == null) {
            return lastHeard;
        }
        return lastHeard == null || lastHeard.isBefore(startedAt) ? startedAt : lastHeard;
    }

    /** The newest message of the base or of any of its detectors, null when none was heard. */
    Instant lastHeardOf(final String serial) {
        return lastHeard(serial, states.base(serial), registry.all());
    }

    //the newest message of the base itself or of any of its detectors - everything a detector says comes through its base
    private Instant lastHeard(final String serial, final Optional<BaseState> base, final List<Detector> detectors) {
        return Stream.concat(
                base.map(BaseState::lastHeardAt).stream(),
                detectors.stream()
                    .filter(detector -> serial.equals(detector.stationSerial()))
                    .flatMap(detector -> states.detector(detector.id()).map(DetectorState::lastHeardAt).stream()))
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
    }

    //the things no longer watched first, then the returns, the alerts and the reminders, each after the one before
    private Mono<Void> act(final Decision decision, final Instant now) {
        final Instant at = now.truncatedTo(ChronoUnit.MICROS);
        return Flux.fromIterable(decision.forgotten())
            .concatMap(alert -> alertStore.close(alert.item(), at)
                .doOnSuccess(done -> log.info("Technical alert of {} closed: it is no longer watched", alert.item())))
            .then(publish(decision.returns(), notificationPublisher::publishInfo, returnText(decision.returns(), now))
                .flatMap(sent -> Flux.fromIterable(decision.returns())
                    .concatMap(alert -> alertStore.close(alert.item(), at)).then()))
            .then(Flux.fromIterable(decision.alerts())
                .concatMap(alert -> alertStore.due(truncated(alert)))
                .then(publish(decision.alerts(), notificationPublisher::publishAlert,
                    alertText("Safety service - out: ", decision.alerts(), now)))
                .flatMap(sent -> Flux.fromIterable(decision.alerts())
                    .concatMap(alert -> alertStore.told(alert.item(), at)).then()))
            .then(publish(decision.reminders(), notificationPublisher::publishReminder,
                alertText("Safety service - still out: ", decision.reminders(), now))
                .flatMap(sent -> Flux.fromIterable(decision.reminders())
                    .concatMap(alert -> alertStore.told(alert.item(), at)).then()));
    }

    //true once the broker took it; empty when there was nothing to say or it failed (logged, tried again)
    private Mono<Boolean> publish(final List<Alert> alerts, final Function<String, Mono<Void>> publisher,
                                  final String text) {
        return Mono.just(alerts)
            .filter(due -> !due.isEmpty())
            .flatMap(due -> publisher.apply(text)
                .timeout(PUBLISH_TIMEOUT)
                .thenReturn(true)
                .doOnNext(sent -> log.info("Technical notification published: {}", text))
                .onErrorResume(failure -> Mono.fromRunnable(() -> log.error(
                    "Technical notification not published, it is tried again with the next pass: {}",
                    failure.toString()))));
    }

    String alertText(final String lead, final List<Alert> alerts, final Instant now) {
        return lead + list(alerts, alert -> alert.name() + " " + alert.kind().getProblem() + " since "
            + time(alert.since()) + " (" + span(alert.since(), now) + ")") + ".";
    }

    String returnText(final List<Alert> alerts, final Instant now) {
        return "Safety service - back: " + list(alerts, alert -> alert.name() + " - " + alert.kind().getProblem()
            + " from " + time(alert.since()) + " to " + time(now) + " (" + span(alert.since(), now) + ")"
            + (alert.told() ? "" : ", its alert never got through")) + ".";
    }

    private String time(final Instant instant) {
        return TIME.format(LocalDateTime.ofInstant(instant, clock.getZone()));
    }

    private static String list(final List<Alert> alerts, final Function<Alert, String> line) {
        return alerts.stream().map(line).collect(Collectors.joining("; "));
    }

    private static String span(final Instant since, final Instant until) {
        final long total = Duration.between(since, until).toMinutes();
        return total < 60 ? total + " min" : total / 60 + " h " + total % 60 + " min";
    }

    private static Alert truncated(final Alert alert) {
        return new Alert(alert.kind(), alert.device(), alert.name(), alert.since().truncatedTo(ChronoUnit.MICROS),
            alert.told(), alert.lastAlertAt());
    }
}
