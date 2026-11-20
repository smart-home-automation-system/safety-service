package cloud.cholewa.safety.registry;

import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.home.model.XsenseBinding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The fire detectors of the device registry, in memory: the switched-on devices of the kinds smoke, co and heat detector
 * (smart-home-sdk 1.24.0), each with its {@code xsense} binding. Not known until the first read of all three kinds
 * succeeded - the reads of the API answer {@code REGISTRY_UNAVAILABLE} and every message of a base is dropped until then.
 * A failed read keeps the detectors of the read before (a WARN): never an empty registry for a database-service away.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DetectorRegistry {

    static final List<DeviceKind> KINDS = List.of(DeviceKind.SMOKE_DETECTOR, DeviceKind.CO_DETECTOR, DeviceKind.HEAT_DETECTOR);

    private final DeviceRegistryClient client;

    private final AtomicReference<Known> known = new AtomicReference<>();

    /**
     * Reads the detectors again, the three kinds one after the other.
     *
     * @return whether this was the first read that succeeded since the start; an error when a read failed
     */
    public Mono<Boolean> refresh() {
        return Flux.fromIterable(KINDS)
            .concatMap(client::getDevices)
            .flatMapIterable(Function.identity())
            .mapNotNull(DetectorRegistry::detectorOf)
            .collectList()
            .map(detectors -> {
                final Known before = known.getAndSet(new Known(detectors));
                if (before == null || before.byId().size() != detectors.size()) {
                    log.info("The device registry lists {} fire detectors", detectors.size());
                }
                return before == null;
            });
    }

    public boolean isKnown() {
        return known.get() != null;
    }

    /** The detector a base names in a topic; empty when the registry was never read or does not list it. */
    public Optional<Detector> find(final DetectorKey key) {
        return Optional.ofNullable(known.get()).map(Known::byKey).map(byKey -> byKey.get(key));
    }

    /** The detector of a registry id; empty when the registry was never read or does not list it. */
    public Optional<Detector> find(final long id) {
        return Optional.ofNullable(known.get()).map(Known::byId).map(byId -> byId.get(id));
    }

    /** Every detector by its id; empty when the registry was never read - ask {@link #isKnown()} first. */
    public List<Detector> all() {
        return Optional.ofNullable(known.get())
            .map(Known::byId)
            .map(Map::values)
            .map(DetectorRegistry::sorted)
            .orElse(List.of());
    }

    //a device without its binding cannot be found in a topic - the registry refuses one, a reader still checks
    private static Detector detectorOf(final SmartDevice device) {
        final XsenseBinding binding = device.getXsense();
        if (device.getId() == null || binding == null || binding.getStationSerial() == null || binding.getDeviceId() == null) {
            log.warn("The fire detector {} of the device registry has no xsense binding and is left out", device.getId());
            return null;
        }
        final DetectorKey key = new DetectorKey(binding.getStationSerial(), binding.getDeviceId());
        return new Detector(device.getId(), device.getName(), device.getRoom(), device.getKind(),
            key.stationSerial(), key.deviceId());
    }

    private static List<Detector> sorted(final Collection<Detector> detectors) {
        return detectors.stream().sorted(Comparator.comparingLong(Detector::id)).toList();
    }

    private record Known(Map<Long, Detector> byId, Map<DetectorKey, Detector> byKey) {

        //two devices with one pair are refused by the registry (DEVICE_CONFLICT); the first by id would win here
        Known(final List<Detector> detectors) {
            this(detectors.stream().collect(Collectors.toUnmodifiableMap(Detector::id, Function.identity(), (a, b) -> a)),
                detectors.stream().collect(Collectors.toUnmodifiableMap(Detector::key, Function.identity(),
                    (a, b) -> a.id() < b.id() ? a : b)));
        }
    }
}
