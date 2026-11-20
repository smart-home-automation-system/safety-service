package cloud.cholewa.safety.detector;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.safety.api.model.BaseReply;
import cloud.cholewa.safety.api.model.DetectorReply;
import cloud.cholewa.safety.api.model.FlagReply;
import cloud.cholewa.safety.api.model.SafetyReply;
import cloud.cholewa.safety.error.SafetyException;
import cloud.cholewa.safety.registry.Detector;
import cloud.cholewa.safety.registry.DetectorRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The reads of the API, from memory alone: the detectors of the registry with what their base said, and the bases
 * heard. {@code REGISTRY_UNAVAILABLE} until the registry was read once; a detector its base never spoke of is answered
 * with what the registry says of it alone. Times in the zone of the house.
 */
@Service
@RequiredArgsConstructor
public class SafetyService {

    private final DetectorRegistry registry;
    private final SafetyStates states;
    private final Clock clock;

    public Mono<SafetyReply> all() {
        return known().map(detectors -> new SafetyReply(
            detectors.stream().map(this::reply).toList(),
            states.bases().stream()
                .sorted(Comparator.comparing(BaseState::stationSerial))
                .map(this::reply)
                .toList()));
    }

    public Mono<List<DetectorReply>> room(final String room) {
        return known().flatMap(detectors -> roomOf(room)
            .map(name -> detectors.stream().filter(detector -> detector.room() == name).map(this::reply).toList())
            .filter(replies -> !replies.isEmpty())
            .map(Mono::just)
            .orElseGet(() -> Mono.error(new SafetyException.RoomNotFound(room))));
    }

    public Mono<DetectorReply> detector(final long id) {
        return known().flatMap(ignored -> Mono.justOrEmpty(registry.find(id)))
            .map(this::reply)
            .switchIfEmpty(Mono.error(() -> new SafetyException.DetectorNotFound(id)));
    }

    private Mono<List<Detector>> known() {
        return Mono.fromSupplier(registry::isKnown)
            .filter(Boolean::booleanValue)
            .map(ignored -> registry.all())
            .switchIfEmpty(Mono.error(SafetyException.RegistryUnavailable::new));
    }

    private static Optional<RoomName> roomOf(final String room) {
        try {
            return Optional.of(RoomName.fromValue(room));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    private DetectorReply reply(final Detector detector) {
        final Optional<DetectorState> state = states.detector(detector.id());
        return new DetectorReply(
            detector.id(),
            detector.name(),
            detector.room() == null ? null : detector.room().getValue(),
            detector.kind() == null ? null : detector.kind().getValue(),
            detector.stationSerial(),
            detector.deviceId(),
            flag(state, DetectorFlag.ALARM),
            flag(state, DetectorFlag.ONLINE),
            flag(state, DetectorFlag.BATTERY_LOW),
            flag(state, DetectorFlag.END_OF_LIFE),
            flag(state, DetectorFlag.FAULT),
            flag(state, DetectorFlag.MUTED),
            flag(state, DetectorFlag.SELF_TEST_PROBLEM),
            state.map(DetectorState::model).orElse(null),
            state.map(DetectorState::firmware).orElse(null),
            state.map(DetectorState::lastHeardAt).map(this::house).orElse(null),
            state.map(DetectorState::confirmed).orElse(null));
    }

    private BaseReply reply(final BaseState base) {
        return new BaseReply(base.stationSerial(), flag(base.online()), base.ipAddress(), base.wifiSsid(),
            base.wifiRssi(), Optional.ofNullable(base.lastHeardAt()).map(this::house).orElse(null), base.confirmed());
    }

    private FlagReply flag(final Optional<DetectorState> state, final DetectorFlag flag) {
        return state.map(known -> known.flag(flag)).map(this::flag).orElse(null);
    }

    private FlagReply flag(final Flag flag) {
        return flag == null ? null : new FlagReply(flag.value(), house(flag.changedAt()));
    }

    private LocalDateTime house(final Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone());
    }
}
