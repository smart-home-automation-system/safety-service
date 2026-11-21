package cloud.cholewa.safety.xsense;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.safety.command.CommandService;
import cloud.cholewa.safety.command.DetectorCommand;
import cloud.cholewa.safety.database.StateWriter;
import cloud.cholewa.safety.detector.AlarmEpisodes;
import cloud.cholewa.safety.detector.BaseState;
import cloud.cholewa.safety.detector.DetectorFlag;
import cloud.cholewa.safety.detector.DetectorState;
import cloud.cholewa.safety.detector.Flag;
import cloud.cholewa.safety.detector.SafetyStates;
import cloud.cholewa.safety.registry.DetectorRegistry;
import cloud.cholewa.safety.registry.DeviceRegistryClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static cloud.cholewa.safety.registry.TestDevices.STATION;
import static cloud.cholewa.safety.registry.TestDevices.detector;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

//the messages as the base published them (2026-11-16, 2026-11-20), with an invented serial
class XsenseEventRouterTest {

    private static final String ALARM = "homeassistant.binary_sensor.SBS50ABCDEF01_00000001.SBS50ABCDEF01_00000001_smokealarm.state";
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private final DeviceRegistryClient client = mock(DeviceRegistryClient.class);
    private final DetectorRegistry registry = new DetectorRegistry(client);
    private final SafetyStates states = new SafetyStates();
    private final StateWriter store = mock(StateWriter.class);
    private final AlarmEpisodes episodes = mock(AlarmEpisodes.class);
    private final CommandService commands = mock(CommandService.class);
    private final MovableClock clock = new MovableClock(NOW);
    private final XsenseEventRouter router = new XsenseEventRouter(registry, states, store, episodes, commands,
        JsonMapper.builder().build(), clock);
    private final ListAppender<ILoggingEvent> routerLog = new ListAppender<>();

    @BeforeEach
    void setUp() {
        when(client.getDevices(DeviceKind.SMOKE_DETECTOR)).thenReturn(Mono.just(List.of(
            detector(150L, "00000001", RoomName.LIVING_ROOM))));
        when(client.getDevices(DeviceKind.CO_DETECTOR)).thenReturn(Mono.just(List.of()));
        when(client.getDevices(DeviceKind.HEAT_DETECTOR)).thenReturn(Mono.just(List.of()));
        routerLog.start();
        ((Logger) LoggerFactory.getLogger(XsenseEventRouter.class)).addAppender(routerLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(XsenseEventRouter.class)).detachAppender(routerLog);
    }

    //the registry never read: the base is asked to republish once it is, so nothing is lost
    @Test
    void should_drop_every_detector_message_before_the_registry_is_read() {
        route(ALARM, "{\"status\": \"Cleared\"}");

        assertThat(states.detector(150L)).isEmpty();
        verify(store, never()).submit(any(DetectorState.class));
    }

    @Test
    void should_take_a_flag_of_a_detector_and_store_only_a_change() {
        readRegistry();

        route(ALARM, "{\n\t\"status\":\t\"Cleared\"\n}");
        clock.at(NOW.plusSeconds(60));
        route(ALARM, "{\"status\": \"Cleared\"}");
        clock.at(NOW.plusSeconds(120));
        route(ALARM, "{\"status\": \"Detected\"}");

        assertThat(states.detector(150L)).hasValueSatisfying(state -> {
            assertThat(state.flag(DetectorFlag.ALARM)).isEqualTo(new Flag(true, NOW.plusSeconds(120)));
            assertThat(state.lastHeardAt()).isEqualTo(NOW.plusSeconds(120));
            assertThat(state.confirmed()).isTrue();
        });
        //the first message and the change; the repeat confirms in memory only
        verify(store, times(2)).submit(any(DetectorState.class));
    }

    //HAS-461: every smokealarm message goes to the episodes, repeated or not - they decide; no other flag does
    @Test
    void should_hand_every_smokealarm_message_to_the_episodes() {
        readRegistry();

        route(ALARM, "{\"status\": \"Detected\"}");
        route(ALARM, "{\"status\": \"Detected\"}");
        route(ALARM, "{\"status\": \"Cleared\"}");
        route("homeassistant.binary_sensor.SBS50ABCDEF01_00000001.SBS50ABCDEF01_00000001_battery.state",
            "{\"status\": \"Low\"}");
        route(ALARM, "{\"status\": \"Smoke\"}");

        verify(episodes, times(2)).alarm(argThat(detector -> detector.id() == 150L), eq(true));
        verify(episodes).alarm(argThat(detector -> detector.id() == 150L), eq(false));
        verifyNoMoreInteractions(episodes);
    }

    //HAS-464: a muted Muted answers a mute and every message of a self test a self test - after memory changed; muted
    //Normal and the other flags answer nothing, nor does the command topic of the base (our own command coming back)
    @Test
    void should_hand_the_answers_of_the_base_to_the_commands() {
        readRegistry();
        final String muted = "homeassistant.binary_sensor.SBS50ABCDEF01_00000001.SBS50ABCDEF01_00000001_muted.state";

        route(muted, "{\"status\": \"Normal\"}");
        route(muted, "{\"status\": \"Muted\"}");
        route("xsense.sbs50.SBS50ABCDEF01_00000001.self_test.state", "{\"problem\": false}");
        route(ALARM, "{\"status\": \"Detected\"}");
        route("xsense.sbs50.SBS50ABCDEF01.device.00000001.cmd",
            "{\"requestId\": \"sh-1\", \"cmd\": \"mute\", \"params\": {}}");

        verify(commands).reported(150L, DetectorCommand.MUTE);
        verify(commands).selfTestReported(150L, null);
        verifyNoMoreInteractions(commands);
        assertThat(states.detector(150L)).hasValueSatisfying(state ->
            assertThat(state.flag(DetectorFlag.MUTED).value()).isTrue());
    }

    @Test
    void should_read_every_flag_of_the_smoke_detector() {
        readRegistry();
        final Map<String, String> words = Map.of("online", "Offline", "battery", "Low", "lifeend", "EOL",
            "smokefault", "Fault", "muted", "Muted");

        words.forEach((entity, word) -> route(
            "homeassistant.binary_sensor.SBS50ABCDEF01_00000001.SBS50ABCDEF01_00000001_" + entity + ".state",
            "{\"status\": \"" + word + "\"}"));
        route("xsense.sbs50.SBS50ABCDEF01_00000001.self_test.state", "{\"problem\": true, \"time\": 1763632800}");

        assertThat(states.detector(150L)).hasValueSatisfying(state -> {
            assertThat(state.flag(DetectorFlag.ONLINE).value()).isFalse();
            assertThat(state.flag(DetectorFlag.BATTERY_LOW).value()).isTrue();
            assertThat(state.flag(DetectorFlag.END_OF_LIFE).value()).isTrue();
            assertThat(state.flag(DetectorFlag.FAULT).value()).isTrue();
            assertThat(state.flag(DetectorFlag.MUTED).value()).isTrue();
            assertThat(state.flag(DetectorFlag.SELF_TEST_PROBLEM).value()).isTrue();
            assertThat(state.flag(DetectorFlag.ALARM)).isNull();
        });
        //HAS-464: the time of the test goes with it to the commands
        verify(commands).selfTestReported(150L, Instant.ofEpochSecond(1763632800L));
    }

    @Test
    void should_take_the_model_and_firmware_of_the_discovery() {
        readRegistry();

        route("homeassistant.binary_sensor.SBS50ABCDEF01_00000001.SBS50ABCDEF01_00000001_smokealarm.config", """
            {"device":{"identifiers":["SBS50ABCDEF01_00000001"],"name":"Smoke Alarm(SBS50ABCDEF01_00000001)",
             "manufacturer":"X-SENSE","model":"XS01-M","sw_version":"v1.8.0"},"name":"Smoke Status",
             "device_class":"smoke","payload_on":"Detected","payload_off":"Cleared"}
            """);

        assertThat(states.detector(150L)).hasValueSatisfying(state -> {
            assertThat(state.model()).isEqualTo("XS01-M");
            assertThat(state.firmware()).isEqualTo("v1.8.0");
        });
    }

    @Test
    void should_drop_a_detector_the_registry_does_not_list() {
        readRegistry();

        route("homeassistant.binary_sensor.SBS50ABCDEF01_00000009.SBS50ABCDEF01_00000009_smokealarm.state",
            "{\"status\": \"Detected\"}");

        verify(store, never()).submit(any(DetectorState.class));
        assertThat(routerLog.list).isEmpty();
    }

    //a word neither of the two changes nothing and is told once per flag and word
    @Test
    void should_warn_once_of_a_word_it_does_not_know() {
        readRegistry();

        route(ALARM, "{\"status\": \"Testing\"}");
        route(ALARM, "{\"status\": \"Testing\"}");

        assertThat(states.detector(150L)).isEmpty();
        assertThat(routerLog.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("Testing", "Detected", "Cleared");
        });
    }

    //an empty body - a discovery removed, a retained message cleared - says nothing
    @Test
    void should_drop_an_empty_body_without_a_word() {
        readRegistry();

        route("homeassistant.binary_sensor.SBS50ABCDEF01_00000001.SBS50ABCDEF01_00000001_smokealarm.config", "");

        assertThat(states.detector(150L)).isEmpty();
        assertThat(routerLog.list).isEmpty();
    }

    //nothing changes for a caller that never subscribes
    @Test
    void should_change_nothing_before_the_subscription() {
        router.route("xsense.sbs50.SBS50ABCDEF01.availability", "online".getBytes(StandardCharsets.UTF_8));

        assertThat(states.base(STATION)).isEmpty();
    }

    @Test
    void should_warn_of_a_body_that_is_not_the_model() {
        readRegistry();

        route(ALARM, "not json");
        route(ALARM, "{}");

        assertThat(states.detector(150L)).isEmpty();
        assertThat(routerLog.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.WARN);
    }

    //an entity not read yet (a CO detector names its alarm otherwise) is told once, at INFO
    @Test
    void should_tell_once_of_an_entity_it_does_not_read() {
        readRegistry();

        route("homeassistant.binary_sensor.SBS50ABCDEF01_00000001.SBS50ABCDEF01_00000001_coalarm.state",
            "{\"status\": \"Detected\"}");
        route("homeassistant.binary_sensor.SBS50ABCDEF01_00000001.SBS50ABCDEF01_00000001_coalarm.state",
            "{\"status\": \"Detected\"}");

        assertThat(routerLog.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("coalarm");
        });
    }

    //the base is not a registry device: heard without a read of the registry
    @Test
    void should_take_the_availability_and_the_network_of_the_base() {
        route("xsense.sbs50.SBS50ABCDEF01.availability", "online");
        route("xsense.sbs50.SBS50ABCDEF01.network.state",
            "{\"valid\":true,\"ip_address\":\"192.0.2.82\",\"wifi_ssid\":\"iot\",\"wifi_rssi\":-35}");
        clock.at(NOW.plusSeconds(60));
        //the RSSI alone moved: kept, not stored
        route("xsense.sbs50.SBS50ABCDEF01.network.state",
            "{\"valid\":true,\"ip_address\":\"192.0.2.82\",\"wifi_ssid\":\"iot\",\"wifi_rssi\":-33}");

        assertThat(states.base(STATION)).hasValueSatisfying(base -> {
            assertThat(base.online()).isEqualTo(new Flag(true, NOW));
            assertThat(base.ipAddress()).isEqualTo("192.0.2.82");
            assertThat(base.wifiSsid()).isEqualTo("iot");
            assertThat(base.wifiRssi()).isEqualTo(-33);
            assertThat(base.lastHeardAt()).isEqualTo(NOW.plusSeconds(60));
        });
        verify(store, times(2)).submit(any(BaseState.class));
    }

    //offline - its own word or the last will the broker publishes when it drops
    @Test
    void should_take_the_last_will_of_the_base_and_refuse_another_word() {
        route("xsense.sbs50.SBS50ABCDEF01.availability", "online");
        clock.at(NOW.plusSeconds(60));
        route("xsense.sbs50.SBS50ABCDEF01.availability", "offline");
        route("xsense.sbs50.SBS50ABCDEF01.availability", "maybe");

        assertThat(states.base(STATION)).hasValueSatisfying(base ->
            assertThat(base.online()).isEqualTo(new Flag(false, NOW.plusSeconds(60))));
        assertThat(routerLog.list).singleElement().satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    void should_drop_every_other_device_without_a_word() {
        route("shellyplus1pm-0123456789ab.status.switch:0", "{\"id\":0,\"output\":true}");
        route("homeassistant.status", "online");

        assertThat(routerLog.list).isEmpty();
        verify(store, never()).submit(any(DetectorState.class));
        verify(store, never()).submit(any(BaseState.class));
    }

    private void readRegistry() {
        registry.refresh().as(StepVerifier::create).expectNext(true).verifyComplete();
    }

    private void route(final String routingKey, final String body) {
        router.route(routingKey, body.getBytes(StandardCharsets.UTF_8)).as(StepVerifier::create).verifyComplete();
    }

    static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(final Instant now) {
            this.now = now;
        }

        void at(final Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Europe/Warsaw");
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
