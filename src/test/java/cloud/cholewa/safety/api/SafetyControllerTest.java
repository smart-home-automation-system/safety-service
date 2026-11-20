package cloud.cholewa.safety.api;

import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.safety.config.ExceptionHandlerConfig;
import cloud.cholewa.safety.detector.BaseState;
import cloud.cholewa.safety.detector.DetectorFlag;
import cloud.cholewa.safety.detector.DetectorState;
import cloud.cholewa.safety.detector.SafetyService;
import cloud.cholewa.safety.detector.SafetyStates;
import cloud.cholewa.safety.registry.DetectorRegistry;
import cloud.cholewa.safety.registry.DeviceRegistryClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static cloud.cholewa.safety.registry.TestDevices.STATION;
import static cloud.cholewa.safety.registry.TestDevices.detector;
import static org.mockito.Mockito.when;

//the reads of HAS-460 through the controller, the service and the state in memory - the fields are wire contract for
//the dashboard; a field not known is left out, never a default. Times in the zone of the house (UTC+1 in November)
@WebFluxTest(SafetyController.class)
@Import({ExceptionHandlerConfig.class, SafetyService.class, SafetyStates.class, DetectorRegistry.class,
    SafetyControllerTest.FixedClock.class})
@ActiveProfiles("test")
class SafetyControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    @Autowired
    private WebTestClient webTestClient;
    @Autowired
    private DetectorRegistry registry;
    @Autowired
    private SafetyStates states;

    @MockitoBean
    private DeviceRegistryClient client;

    @Test
    void should_refuse_every_read_before_the_registry_is_read() {
        webTestClient.get().uri("/").exchange()
            .expectStatus().isEqualTo(503)
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("REGISTRY_UNAVAILABLE");
    }

    @Test
    void should_answer_every_detector_with_what_its_base_said_and_the_base() {
        readRegistry();
        states.changeDetector(150L, id -> DetectorState.unknown(id, NOW), state -> state
            .withFlag(DetectorFlag.ALARM, false, NOW)
            .withFlag(DetectorFlag.ONLINE, true, NOW)
            .withDevice("XS01-M", "v1.8.0", NOW));
        states.changeBase(STATION, serial -> BaseState.unknown(serial, NOW),
            base -> base.withOnline(true, NOW).withNetwork("192.0.2.82", "iot", -35, NOW));

        webTestClient.get().uri("/").exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.detectors.length()").isEqualTo(2)
            .jsonPath("$.detectors[0].id").isEqualTo(150)
            .jsonPath("$.detectors[0].name").isEqualTo("Smoke detector")
            .jsonPath("$.detectors[0].room").isEqualTo("living room")
            .jsonPath("$.detectors[0].kind").isEqualTo("smoke detector")
            .jsonPath("$.detectors[0].stationSerial").isEqualTo(STATION)
            .jsonPath("$.detectors[0].deviceId").isEqualTo("00000001")
            .jsonPath("$.detectors[0].alarm.value").isEqualTo(false)
            .jsonPath("$.detectors[0].alarm.since").isEqualTo("2026-11-20T11:00:00")
            .jsonPath("$.detectors[0].online.value").isEqualTo(true)
            .jsonPath("$.detectors[0].batteryLow").doesNotExist()
            .jsonPath("$.detectors[0].model").isEqualTo("XS01-M")
            .jsonPath("$.detectors[0].firmware").isEqualTo("v1.8.0")
            .jsonPath("$.detectors[0].lastHeardAt").isEqualTo("2026-11-20T11:00:00")
            .jsonPath("$.detectors[0].confirmed").isEqualTo(true)
            //never heard: what the registry says alone
            .jsonPath("$.detectors[1].id").isEqualTo(151)
            .jsonPath("$.detectors[1].alarm").doesNotExist()
            .jsonPath("$.detectors[1].confirmed").doesNotExist()
            .jsonPath("$.bases.length()").isEqualTo(1)
            .jsonPath("$.bases[0].stationSerial").isEqualTo(STATION)
            .jsonPath("$.bases[0].online.value").isEqualTo(true)
            .jsonPath("$.bases[0].ipAddress").isEqualTo("192.0.2.82")
            .jsonPath("$.bases[0].wifiSsid").isEqualTo("iot")
            .jsonPath("$.bases[0].wifiRssi").isEqualTo(-35)
            .jsonPath("$.bases[0].confirmed").isEqualTo(true);
    }

    @Test
    void should_answer_the_detectors_of_a_room() {
        readRegistry();

        webTestClient.get().uri("/rooms/{room}", "living room").exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].id").isEqualTo(150);
    }

    @Test
    void should_refuse_a_room_without_a_detector_or_unknown() {
        readRegistry();

        webTestClient.get().uri("/rooms/{room}", "sauna").exchange()
            .expectStatus().isNotFound()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_ROOM");
        webTestClient.get().uri("/rooms/{room}", "attic").exchange()
            .expectStatus().isNotFound()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_ROOM");
    }

    @Test
    void should_answer_one_detector_and_refuse_an_unknown_one() {
        readRegistry();

        webTestClient.get().uri("/151").exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.room").isEqualTo("kitchen");
        webTestClient.get().uri("/999").exchange()
            .expectStatus().isNotFound()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_DETECTOR");
    }

    private void readRegistry() {
        when(client.getDevices(DeviceKind.SMOKE_DETECTOR)).thenReturn(Mono.just(List.of(
            detector(151L, "00000002", RoomName.KITCHEN), detector(150L, "00000001", RoomName.LIVING_ROOM))));
        when(client.getDevices(DeviceKind.CO_DETECTOR)).thenReturn(Mono.just(List.of()));
        when(client.getDevices(DeviceKind.HEAT_DETECTOR)).thenReturn(Mono.just(List.of()));
        registry.refresh().as(StepVerifier::create).expectNextCount(1).verifyComplete();
    }

    @TestConfiguration
    static class FixedClock {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneId.of("Europe/Warsaw"));
        }
    }
}
