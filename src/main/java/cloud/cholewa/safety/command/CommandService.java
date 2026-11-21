package cloud.cholewa.safety.command;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.safety.api.model.CommandRecordReply;
import cloud.cholewa.safety.api.model.CommandReply;
import cloud.cholewa.safety.database.CommandStore;
import cloud.cholewa.safety.error.SafetyException;
import cloud.cholewa.safety.registry.Detector;
import cloud.cholewa.safety.registry.DetectorRegistry;
import cloud.cholewa.safety.xsense.CommandMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * The mute and the self test of a detector (HAS-464) - the first commands ever sent to the X-Sense base: one command
 * per detector a minute ({@code COMMAND_TOO_SOON}), published once to the command topic of the detector
 * ({@link CommandMessage}) and never repeated. A command sent is stored ({@code safety_command}) and waits
 * {@link #WINDOW} for the answer of the base - a mute for {@code muted} Muted, a self test for a message of its self
 * test ({@link #reported}, {@link #selfTestReported}, from the router of the device events) - the one proof that the
 * base got it: a send that returns proves no more (no publisher confirms on the connection). Nothing within the window:
 * a WARN, {@code lastCommandConfirmed} false in the reads, the third in a row of a detector an alert on the technical
 * channel (once a run). A send cut off by its timeout may still go out: it is taken like a sent one and waits for its
 * answer. A send that failed gives its minute back and answers {@code COMMAND_NOT_SENT}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommandService {

    static final Duration WINDOW = Duration.ofSeconds(60);
    static final Duration STORE_TIMEOUT = Duration.ofSeconds(5);
    static final int ALERT_IN_A_ROW = 3;
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(15);
    static final int HISTORY = 50;
    //the clock of the base may lag behind the cluster: a self test it dates up to this before the sending still answers it
    static final Duration BASE_CLOCK_TOLERANCE = Duration.ofSeconds(30);

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final DetectorRegistry registry;
    private final DetectorCommands commands;
    private final CommandMessage message;
    private final CommandStore store;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    //the insert of each command waiting for its answer: its confirmation is written only after its row (a fast answer
    //would update no row otherwise); removed at the end of the window
    private final Map<String, Mono<Void>> inserts = new ConcurrentHashMap<>();

    public Mono<CommandReply> command(final long id, final DetectorCommand command, final CommandSource source) {
        return known(id).flatMap(detector -> Mono.fromSupplier(this::now)
            .filter(now -> commands.reserve(id, now))
            .switchIfEmpty(Mono.error(() -> new SafetyException.CommandTooSoon(id)))
            .flatMap(now -> send(detector, new CommandWatch.Pending("sh-" + UUID.randomUUID(), command, now), source)));
    }

    /** The last {@link #HISTORY} commands of the detector, the newest first. */
    public Mono<List<CommandRecordReply>> history(final long id) {
        return known(id).flatMap(detector -> store.last(id, HISTORY)
            .map(row -> new CommandRecordReply(row.requestId(), row.command(), row.source(), house(row.sentAt()),
                house(row.confirmedAt())))
            .collectList());
    }

    /** An answer of the base taken now - called by the router once memory changed; confirms the command it answers. */
    public void reported(final long id, final DetectorCommand command) {
        answered(id, command, now());
    }

    /**
     * A message of the self test of a detector: dated by the base ({@code testedAt}, its {@code time}) it answers only a
     * self test sent before it - the base republishes the result of the last test at every resync; without a date it
     * answers as it is taken.
     */
    public void selfTestReported(final long id, final Instant testedAt) {
        answered(id, DetectorCommand.SELF_TEST, testedAt == null ? now() : testedAt.plus(BASE_CLOCK_TOLERANCE));
    }

    private void answered(final long id, final DetectorCommand command, final Instant answeredAt) {
        commands.reported(id, command, answeredAt).ifPresent(pending -> {
            final Instant at = now();
            log.info("The detector {} answered the command {} - request {}", id, command.getValue(),
                pending.requestId());
            inserts.getOrDefault(pending.requestId(), Mono.empty())
                .then(store.confirm(pending.requestId(), at).timeout(STORE_TIMEOUT))
                .contextCapture()
                .subscribe(null, failure -> log.warn("The answer to the request {} could not be stored: {}",
                    pending.requestId(), failure.toString()));
        });
    }

    private Mono<Detector> known(final long id) {
        return Mono.fromSupplier(registry::isKnown)
            .filter(Boolean::booleanValue)
            .switchIfEmpty(Mono.error(SafetyException.RegistryUnavailable::new))
            .flatMap(ignored -> Mono.justOrEmpty(registry.find(id)))
            .switchIfEmpty(Mono.error(() -> new SafetyException.DetectorNotFound(id)));
    }

    private Mono<CommandReply> send(final Detector detector, final CommandWatch.Pending pending,
                                    final CommandSource source) {
        return message.send(detector.stationSerial(), detector.deviceId(), pending.requestId(), pending.command())
            .doOnSubscribe(subscription -> log.info("The detector {} ({}) is sent the command {} by {} - request {}",
                detector.id(), detector.deviceId(), pending.command().getValue(), source.getValue(),
                pending.requestId()))
            .onErrorResume(TimeoutException.class, timeout -> Mono.fromRunnable(() -> log.warn(
                "The send of the command {} of the detector {} was cut off after {} - it may still go out, so it waits "
                    + "for its answer like a sent one", pending.command().getValue(), detector.id(),
                CommandMessage.SEND_TIMEOUT)))
            .onErrorResume(failure -> Mono.fromRunnable(() -> {
                    commands.release(detector.id(), pending.sentAt());
                    log.warn("The command {} of the detector {} was not sent: {}", pending.command().getValue(),
                        detector.id(), failure.toString());
                })
                .then(Mono.error(new SafetyException.CommandNotSent(detector.id()))))
            .then(Mono.defer(() -> taken(detector, pending, source)));
    }

    //the command is taken whether its row is stored or not: what the base does matters more than its log
    private Mono<CommandReply> taken(final Detector detector, final CommandWatch.Pending pending,
                                     final CommandSource source) {
        final Mono<Void> inserted = store.insert(detector.id(), pending.command().getValue(), source.getValue(),
                pending.requestId(), pending.sentAt())
            .timeout(STORE_TIMEOUT)
            .onErrorResume(failure -> Mono.fromRunnable(() -> log.warn(
                "The command {} of the detector {} could not be stored: {}", pending.command().getValue(),
                detector.id(), failure.toString())))
            .cache();
        return Mono.fromRunnable(() -> {
                inserts.put(pending.requestId(), inserted);
                commands.issued(detector.id(), pending);
                watch(detector, pending);
            })
            .then(inserted)
            .thenReturn(new CommandReply(detector.id(), pending.command().getValue(), source.getValue(),
                pending.requestId(), houseOf(pending.sentAt()).truncatedTo(ChronoUnit.SECONDS)));
    }

    private void watch(final Detector detector, final CommandWatch.Pending pending) {
        confirmation(detector, pending)
            .contextCapture()
            .subscribe(null, failure -> log.error("The confirmation of the request {} ended with an error",
                pending.requestId(), failure));
    }

    Mono<Void> confirmation(final Detector detector, final CommandWatch.Pending pending) {
        return Mono.delay(WINDOW)
            .doOnNext(tick -> inserts.remove(pending.requestId()))
            .flatMap(tick -> Mono.justOrEmpty(commands.expired(detector.id(), pending.requestId())))
            .flatMap(after -> {
                log.warn("The base reported nothing that answers the command {} of the detector {} within {} - request "
                        + "{}: unconfirmed ({} in a row)", pending.command().getValue(), detector.id(), WINDOW,
                    pending.requestId(), after.inARow());
                return Mono.just(after)
                    .filter(run -> run.inARow() >= ALERT_IN_A_ROW && !run.announced())
                    .flatMap(run -> alert(detector, pending, run.inARow()));
            });
    }

    //not announced when the alert fails: the next unconfirmed command tries again
    private Mono<Void> alert(final Detector detector, final CommandWatch.Pending pending, final int inARow) {
        final String text = alertText(detector, pending, inARow, houseOf(pending.sentAt()));
        return notificationPublisher.publishAlert(text)
            .timeout(NOTICE_TIMEOUT)
            .then(Mono.<Void>fromRunnable(() -> {
                commands.announced(detector.id());
                log.info("Detector command notification published: {}", text);
            }))
            .onErrorResume(failure -> Mono.<Void>fromRunnable(() -> log.error(
                "The detector command notification was not published, the next unconfirmed command tries again - "
                    + "{}: {}", failure.toString(), text)));
    }

    //"The detector Smoke detector (kitchen) has not confirmed 3 commands in a row: the last one, mute at 10:05, was not
    //answered by its base within 60 s."
    static String alertText(final Detector detector, final CommandWatch.Pending pending, final int inARow,
                            final LocalDateTime sentAt) {
        final String room = detector.room() == null ? "" : " (" + detector.room().getValue() + ")";
        final String name = detector.name() == null ? detector.deviceId() : detector.name();
        return "The detector " + name + room + " has not confirmed " + inARow + " commands in a row: the "
            + "last one, " + pending.command().getValue() + " at " + HOUR.format(sentAt) + ", was not answered by its "
            + "base within " + WINDOW.toSeconds() + " s.";
    }

    private Instant now() {
        return clock.instant();
    }

    //a time that may be missing (a command not confirmed): null for none
    private LocalDateTime house(final Instant instant) {
        return instant == null ? null : houseOf(instant);
    }

    private LocalDateTime houseOf(final Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone());
    }
}
