package cloud.cholewa.safety.registry;

import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartDevice;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static cloud.cholewa.safety.registry.TestDevices.STATION;
import static cloud.cholewa.safety.registry.TestDevices.detector;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DetectorRegistryTest {

    private final DeviceRegistryClient client = mock(DeviceRegistryClient.class);
    private final DetectorRegistry registry = new DetectorRegistry(client);

    @Test
    void should_know_no_detector_before_the_first_read() {
        assertThat(registry.isKnown()).isFalse();
        assertThat(registry.all()).isEmpty();
        assertThat(registry.find(150L)).isEmpty();
        assertThat(registry.find(new DetectorKey(STATION, "00000001"))).isEmpty();
    }

    //the three kinds read one after the other; the first read that succeeds says so - it sends the birth message
    @Test
    void should_read_the_three_kinds_and_find_a_detector_by_its_base_and_id() {
        answer(List.of(detector(151L, "00000002", RoomName.KITCHEN)), List.of(detector(150L, "0000000a", RoomName.LIVING_ROOM)),
            List.of());

        registry.refresh().as(StepVerifier::create).expectNext(true).verifyComplete();
        registry.refresh().as(StepVerifier::create).expectNext(false).verifyComplete();

        assertThat(registry.all()).extracting(Detector::id).containsExactly(150L, 151L);
        assertThat(registry.find(new DetectorKey("sbs50abcdef01", "0000000A")))
            .hasValueSatisfying(found -> assertThat(found.id()).isEqualTo(150L));
        assertThat(registry.find(151L)).hasValueSatisfying(found -> {
            assertThat(found.room()).isEqualTo(RoomName.KITCHEN);
            assertThat(found.stationSerial()).isEqualTo(STATION);
        });
    }

    //a failed read is never an empty registry: the detectors of the read before stay
    @Test
    void should_keep_the_detectors_when_a_read_fails() {
        answer(List.of(detector(150L, "00000001", RoomName.LIVING_ROOM)), List.of(), List.of());
        registry.refresh().as(StepVerifier::create).expectNext(true).verifyComplete();

        when(client.getDevices(DeviceKind.SMOKE_DETECTOR)).thenReturn(Mono.error(new RegistryCallException("down")));
        registry.refresh().as(StepVerifier::create).expectError(RegistryCallException.class).verify();

        assertThat(registry.all()).extracting(Detector::id).containsExactly(150L);
    }

    @Test
    void should_leave_out_a_detector_without_its_binding() {
        final SmartDevice unbound = detector(152L, "00000003", RoomName.CINEMA).xsense(null);
        answer(List.of(unbound, detector(150L, "00000001", RoomName.LIVING_ROOM)), List.of(), List.of());

        registry.refresh().as(StepVerifier::create).expectNext(true).verifyComplete();

        assertThat(registry.all()).extracting(Detector::id).containsExactly(150L);
    }

    private void answer(final List<SmartDevice> smoke, final List<SmartDevice> co, final List<SmartDevice> heat) {
        when(client.getDevices(DeviceKind.SMOKE_DETECTOR)).thenReturn(Mono.just(smoke));
        when(client.getDevices(DeviceKind.CO_DETECTOR)).thenReturn(Mono.just(co));
        when(client.getDevices(DeviceKind.HEAT_DETECTOR)).thenReturn(Mono.just(heat));
    }
}
