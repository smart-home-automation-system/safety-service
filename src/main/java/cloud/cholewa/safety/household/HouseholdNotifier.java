package cloud.cholewa.safety.household;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.safety.registry.Detector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Tells the household of a fire alarm (HAS-461) - the household category of the notification exchange, worded in Polish
 * by notification-service >= 0.14.0 (HAS-462), texted and pushed to every member at home or not: {@code fire alarm} when
 * an episode opens, {@code fire alarm over} when it closes, the detector of the registry the subject and its room the
 * room. Never a reminder - the detectors sound themselves. It never fails nor stalls the episode it reports on: a
 * failure is a WARN and {@code false}, a publish is cut off after {@link #PUBLISH_TIMEOUT}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HouseholdNotifier {

    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(30);

    private final NotificationPublisher notificationPublisher;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /** True once the broker confirmed it, false when it could not be told - never an error. */
    public Mono<Boolean> fireAlarm(final Detector detector, final Instant at) {
        return Mono.defer(() -> publish(notification(HouseholdEvent.FIRE_ALARM, detector, at)));
    }

    /** As {@link #fireAlarm} for the end of the alarm. */
    public Mono<Boolean> fireAlarmOver(final Detector detector, final Instant at) {
        return Mono.defer(() -> publish(notification(HouseholdEvent.FIRE_ALARM_OVER, detector, at)));
    }

    private HouseholdNotification notification(final HouseholdEvent event, final Detector detector, final Instant at) {
        return HouseholdNotification.builder()
            .event(event)
            .subject(detector.name())
            .deviceId(detector.id())
            .room(detector.room())
            //the local time of the house, as the payload carries it
            .occurredAt(LocalDateTime.ofInstant(at, clock.getZone()))
            .reminder(0)
            .build();
    }

    private Mono<Boolean> publish(final HouseholdNotification notification) {
        return Mono.fromCallable(() -> jsonMapper.writeValueAsString(notification))
            .flatMap(notificationPublisher::publishHousehold)
            .timeout(PUBLISH_TIMEOUT)
            .then(Mono.fromCallable(() -> {
                log.info("The household told: {} of {} ({})", notification.getEvent(), notification.getSubject(),
                    notification.getDeviceId());
                return true;
            }))
            .onErrorResume(failure -> {
                log.warn("The household could not be told: {} of {} ({}) - {}", notification.getEvent(),
                    notification.getSubject(), notification.getDeviceId(), failure.toString());
                return Mono.just(false);
            });
    }
}
