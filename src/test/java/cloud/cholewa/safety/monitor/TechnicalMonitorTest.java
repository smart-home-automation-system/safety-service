package cloud.cholewa.safety.monitor;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.safety.config.SafetyProperties;
import cloud.cholewa.safety.detector.BaseState;
import cloud.cholewa.safety.detector.DetectorFlag;
import cloud.cholewa.safety.detector.DetectorState;
import cloud.cholewa.safety.detector.SafetyStates;
import cloud.cholewa.safety.monitor.AlertEvaluator.Alert;
import cloud.cholewa.safety.monitor.AlertEvaluator.Watched;
import cloud.cholewa.safety.registry.DetectorRegistry;
import cloud.cholewa.safety.registry.DeviceRegistryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.function.UnaryOperator;

import static cloud.cholewa.safety.registry.TestDevices.STATION;
import static cloud.cholewa.safety.registry.TestDevices.detector;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//the technical monitor of HAS-463: what is watched from the reports of the base, and what one pass tells and stores
class TechnicalMonitorTest {

    private static final Instant NOW = Instant.parse("2026-11-20T18:00:00Z");

    private final DeviceRegistryClient client = mock(DeviceRegistryClient.class);
    private final DetectorRegistry registry = new DetectorRegistry(client);
    private final SafetyStates states = new SafetyStates();
    private final AlertStore alertStore = mock(AlertStore.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final SafetyProperties properties = new SafetyProperties(
        new SafetyProperties.Registry(Duration.ofMinutes(5)),
        new SafetyProperties.Base(Duration.ofMinutes(10), Duration.ofMinutes(30)),
        new SafetyProperties.Monitor(Duration.ofHours(1)));
    private final TechnicalMonitor sut = new TechnicalMonitor(registry, states, alertStore, publisher, properties,
        Clock.fixed(NOW, ZoneId.of("Europe/Warsaw")));

    @BeforeEach
    void setUp() {
        when(client.getDevices(DeviceKind.SMOKE_DETECTOR)).thenReturn(Mono.just(List.of(
            detector(151L, "00000002", RoomName.KITCHEN), detector(152L, "00000003", RoomName.CINEMA))));
        when(client.getDevices(DeviceKind.CO_DETECTOR)).thenReturn(Mono.just(List.of()));
        when(client.getDevices(DeviceKind.HEAT_DETECTOR)).thenReturn(Mono.just(List.of()));
        when(alertStore.due(any())).thenReturn(Mono.empty());
        when(alertStore.told(anyString(), any())).thenReturn(Mono.empty());
        when(alertStore.close(anyString(), any())).thenReturn(Mono.empty());
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(publisher.publishReminder(anyString())).thenReturn(Mono.empty());
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
    }

    //every detector with its four problems, every base with its two; a flag the base never reported is not known
    @Test
    void should_watch_the_problems_the_base_reports() {
        registry.refresh().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        report(151L, state -> state
            .withFlag(DetectorFlag.ONLINE, false, NOW.minusSeconds(120))
            .withFlag(DetectorFlag.BATTERY_LOW, true, NOW.minusSeconds(300))
            .withFlag(DetectorFlag.END_OF_LIFE, false, NOW.minusSeconds(300)));
        states.changeBase(STATION, serial -> BaseState.unknown(serial, NOW.minusSeconds(60)),
            base -> base.withOnline(true, NOW.minusSeconds(60)));

        final List<Watched> watched = sut.watched(NOW);

        assertThat(watched).hasSize(10);
        assertThat(find(watched, "offline:151").since()).isEqualTo(NOW.minusSeconds(120));
        assertThat(find(watched, "battery:151").since()).isEqualTo(NOW.minusSeconds(300));
        assertThat(find(watched, "end of life:151").since()).isNull();
        assertThat(find(watched, "end of life:151").known()).isTrue();
        assertThat(find(watched, "fault:151").known()).isFalse();
        assertThat(find(watched, "offline:152").known()).isFalse();
        assertThat(find(watched, "base offline:" + STATION).since()).isNull();
        assertThat(find(watched, "base offline:" + STATION).known()).isTrue();
        assertThat(find(watched, "base silent:" + STATION).since()).isNull();
        assertThat(find(watched, "battery:151").name()).isEqualTo("the detector Smoke detector in kitchen (151)");
    }

    //nothing from the base nor any of its detectors for silent-after: silent since the last message
    @Test
    void should_find_a_base_silent_by_its_newest_message() {
        registry.refresh().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        states.changeBase(STATION, serial -> BaseState.unknown(serial, NOW.minusSeconds(3600)),
            base -> base.withOnline(true, NOW.minusSeconds(3600)));
        report(151L, state -> state.withFlag(DetectorFlag.ONLINE, true, NOW.minusSeconds(1900)));

        assertThat(find(sut.watched(NOW), "base silent:" + STATION).since()).isEqualTo(NOW.minusSeconds(1900));
        report(152L, state -> state.withFlag(DetectorFlag.ONLINE, true, NOW.minusSeconds(60)));
        assertThat(find(sut.watched(NOW), "base silent:" + STATION).since()).isNull();
    }

    //the last will offline, then the base answers the resync without saying online: back, its silence never told apart
    @Test
    void should_take_a_base_back_when_it_speaks_after_its_last_will() {
        registry.refresh().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        states.changeBase(STATION, serial -> BaseState.unknown(serial, NOW.minusSeconds(3600)),
            base -> base.withOnline(false, NOW.minusSeconds(3600)));

        assertThat(find(sut.watched(NOW), "base offline:" + STATION).since()).isEqualTo(NOW.minusSeconds(3600));
        assertThat(find(sut.watched(NOW), "base silent:" + STATION).known()).isFalse();
        report(151L, state -> state.withFlag(DetectorFlag.ONLINE, true, NOW.minusSeconds(60)));
        assertThat(find(sut.watched(NOW), "base offline:" + STATION).since()).isNull();
        assertThat(find(sut.watched(NOW), "base silent:" + STATION).known()).isTrue();
    }

    //after a restart the silence counts from the start at the earliest: a state read back holds the last write
    @Test
    void should_count_the_silence_from_the_start_at_the_earliest() {
        registry.refresh().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        final TechnicalMonitor started = new TechnicalMonitor(registry, states, alertStore, publisher, properties,
            Clock.fixed(NOW.minusSeconds(600), ZoneId.of("Europe/Warsaw")));
        started.markStart();
        states.changeBase(STATION, serial -> BaseState.unknown(serial, NOW.minus(Duration.ofDays(3))),
            base -> base.withOnline(true, NOW.minus(Duration.ofDays(3))));

        assertThat(find(started.watched(NOW), "base silent:" + STATION).since()).isNull();
        assertThat(find(started.watched(NOW.plus(Duration.ofMinutes(25))), "base silent:" + STATION).since())
            .isEqualTo(NOW.minusSeconds(600));
    }

    //one alert naming every problem, written before it is told and marked told once the broker took it; technical only
    @Test
    void should_tell_one_alert_naming_every_problem() {
        registry.refresh().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        when(alertStore.open()).thenReturn(Flux.empty());
        report(151L, state -> state.withFlag(DetectorFlag.BATTERY_LOW, true, NOW.minusSeconds(300)));
        report(152L, state -> state.withFlag(DetectorFlag.FAULT, true, NOW.minusSeconds(60)));

        sut.checkOnce().as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(publisher).publishAlert(text.capture());
        assertThat(text.getValue()).isEqualTo("Safety service - out: the detector Smoke detector in kitchen (151) low on "
            + "battery since 2026-11-20 18:55 (5 min); the detector Smoke detector in cinema (152) faulty since "
            + "2026-11-20 18:59 (1 min).");
        verify(alertStore, times(2)).due(any());
        verify(alertStore).told(eq("battery:151"), any());
        verify(alertStore).told(eq("fault:152"), any());
        verify(publisher, never()).publishInfo(anyString());
    }

    //an alert the broker did not take stays not told - the next pass tells it again
    @Test
    void should_leave_an_alert_not_told_when_the_broker_refused_it() {
        registry.refresh().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        when(alertStore.open()).thenReturn(Flux.empty());
        when(publisher.publishAlert(anyString())).thenReturn(Mono.error(new IllegalStateException("returned")));
        report(151L, state -> state.withFlag(DetectorFlag.FAULT, true, NOW.minusSeconds(60)));

        sut.checkOnce().as(StepVerifier::create).verifyComplete();

        verify(alertStore).due(any());
        verify(alertStore, never()).told(anyString(), any());
    }

    //back: one info, the episode closed; an alert that never got through is announced with it
    @Test
    void should_tell_the_return_and_close_the_episode() {
        registry.refresh().as(StepVerifier::create).expectNextCount(1).verifyComplete();
        when(alertStore.open()).thenReturn(Flux.just(new Alert(AlertKind.OFFLINE, "151",
            "the detector Smoke detector in kitchen (151)", NOW.minusSeconds(4200), false, null)));
        report(151L, state -> state.withFlag(DetectorFlag.ONLINE, true, NOW.minusSeconds(30)));

        sut.checkOnce().as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(publisher).publishInfo(text.capture());
        assertThat(text.getValue()).isEqualTo("Safety service - back: the detector Smoke detector in kitchen (151) - "
            + "offline from 2026-11-20 17:50 to 2026-11-20 19:00 (1 h 10 min), its alert never got through.");
        verify(alertStore).close(eq("offline:151"), any());
    }

    //no pass before the registry is known: every detector would look removed and its alert closed
    @Test
    void should_judge_nothing_before_the_registry_is_known() {
        sut.checkOnce().as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(alertStore, publisher);
    }

    private void report(final long id, final UnaryOperator<DetectorState> change) {
        states.changeDetector(id, known -> DetectorState.unknown(known, NOW.minusSeconds(3600)), change);
    }

    private static Watched find(final List<Watched> watched, final String item) {
        return watched.stream().filter(thing -> thing.item().equals(item)).findFirst().orElseThrow();
    }
}
