package cloud.cholewa.safety.household;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.safety.registry.Detector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//what notification-service >= 0.14.0 (HAS-462) words: the detector of the registry, its room, the local time of the
//house; never a failure for the episode - a broker that refuses or does not answer is false
class HouseholdNotifierTest {

    private static final Instant NOW = Instant.parse("2026-11-20T18:15:00Z");
    private static final Detector KITCHEN = new Detector(151L, "Smoke detector", RoomName.KITCHEN,
        DeviceKind.SMOKE_DETECTOR, "SBS50ABCDEF01", "00000002");

    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final HouseholdNotifier sut = new HouseholdNotifier(publisher, jsonMapper,
        Clock.fixed(NOW, ZoneId.of("Europe/Warsaw")));

    @Test
    void should_publish_a_fire_alarm_and_its_end() {
        when(publisher.publishHousehold(any())).thenReturn(Mono.empty());

        sut.fireAlarm(KITCHEN, NOW).as(StepVerifier::create).expectNext(true).verifyComplete();
        sut.fireAlarmOver(KITCHEN, NOW.plusSeconds(90)).as(StepVerifier::create).expectNext(true).verifyComplete();

        final ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(publisher, times(2)).publishHousehold(json.capture());
        final HouseholdNotification alarm = jsonMapper.readValue(json.getAllValues().get(0), HouseholdNotification.class);
        final HouseholdNotification over = jsonMapper.readValue(json.getAllValues().get(1), HouseholdNotification.class);
        assertThat(alarm.getEvent()).isEqualTo(HouseholdEvent.FIRE_ALARM);
        assertThat(alarm.getSubject()).isEqualTo("Smoke detector");
        assertThat(alarm.getDeviceId()).isEqualTo(151L);
        assertThat(alarm.getRoom()).isEqualTo(RoomName.KITCHEN);
        assertThat(alarm.getOccurredAt()).isEqualTo(LocalDateTime.of(2026, 11, 20, 19, 15));
        assertThat(alarm.getReminder()).isZero();
        assertThat(alarm.getAcknowledgePath()).isNull();
        assertThat(over.getEvent()).isEqualTo(HouseholdEvent.FIRE_ALARM_OVER);
        assertThat(over.getOccurredAt()).isEqualTo(LocalDateTime.of(2026, 11, 20, 19, 16, 30));
    }

    @Test
    void should_answer_false_when_the_broker_refuses() {
        when(publisher.publishHousehold(any())).thenReturn(Mono.error(new IllegalStateException("returned")));

        sut.fireAlarm(KITCHEN, NOW).as(StepVerifier::create).expectNext(false).verifyComplete();
    }

    //a broker that never confirms does not hold the next episode beyond the bound
    @Test
    void should_answer_false_when_the_broker_never_answers() {
        when(publisher.publishHousehold(any())).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> sut.fireAlarmOver(KITCHEN, NOW))
            .expectSubscription()
            .thenAwait(HouseholdNotifier.PUBLISH_TIMEOUT.plus(Duration.ofSeconds(1)))
            .expectNext(false)
            .verifyComplete();
    }
}
