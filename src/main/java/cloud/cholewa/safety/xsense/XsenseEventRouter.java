package cloud.cholewa.safety.xsense;

import cloud.cholewa.safety.database.StateWriter;
import cloud.cholewa.safety.detector.BaseState;
import cloud.cholewa.safety.detector.DetectorFlag;
import cloud.cholewa.safety.detector.DetectorState;
import cloud.cholewa.safety.detector.SafetyStates;
import cloud.cholewa.safety.registry.Detector;
import cloud.cholewa.safety.registry.DetectorKey;
import cloud.cholewa.safety.registry.DetectorRegistry;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Turns a message of an X-Sense base into the state of a detector or of the base (HAS-460). About 130 messages a minute
 * reach the device queue: a routing key that is no topic of a base ({@link XsenseTopic}) is dropped first, then a
 * detector the registry does not list - both without a word. A body that is not what its topic carries, or a word that
 * is neither of the two of its flag, is a WARN and changes nothing (a word only once per flag, a base may repeat it
 * every hour). A changed value is handed to {@link StateWriter} - the message is acknowledged once memory changed; a
 * repeated one only confirms the state in memory.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XsenseEventRouter {

    private final DetectorRegistry registry;
    private final SafetyStates states;
    private final StateWriter writer;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    private final Set<String> unknownWords = ConcurrentHashMap.newKeySet();
    private final Set<String> unreadEntities = ConcurrentHashMap.newKeySet();

    public Mono<Void> route(final String routingKey, final byte[] body) {
        //built at subscription: nothing changes for a caller that never subscribes
        return Mono.defer(() -> XsenseTopic.parse(routingKey)
            .map(topic -> route(topic, routingKey, body))
            .orElseGet(Mono::empty));
    }

    private Mono<Void> route(final XsenseTopic topic, final String routingKey, final byte[] body) {
        return switch (topic) {
            case XsenseTopic.EntityState entity -> detector(entity.detector())
                .map(detector -> entityState(detector, entity.entity(), routingKey, body))
                .orElseGet(Mono::empty);
            case XsenseTopic.EntityConfig config -> detector(config.detector())
                .map(detector -> entityConfig(detector, routingKey, body))
                .orElseGet(Mono::empty);
            case XsenseTopic.SelfTest selfTest -> detector(selfTest.detector())
                .map(detector -> selfTest(detector, routingKey, body))
                .orElseGet(Mono::empty);
            case XsenseTopic.BaseAvailability availability -> baseAvailability(availability.stationSerial(), body);
            case XsenseTopic.BaseNetwork network -> baseNetwork(network.stationSerial(), routingKey, body);
        };
    }

    //a detector of the registry only; the registry never read drops every detector message - the base republishes them
    //at the birth message sent after the first read
    private Optional<Detector> detector(final DetectorKey key) {
        return registry.find(key);
    }

    private Mono<Void> entityState(final Detector detector, final String entity, final String routingKey,
                                   final byte[] body) {
        final Optional<DetectorFlag> flag = DetectorFlag.ofEntity(entity);
        if (flag.isEmpty()) {
            if (unreadEntities.add(entity)) {
                log.info("The entity {} of the X-Sense detectors is not read (first seen on {})", entity, routingKey);
            }
            return Mono.empty();
        }
        return read(routingKey, body, StatusPayload.class)
            .flatMap(payload -> Mono.justOrEmpty(payload.status())
                .switchIfEmpty(Mono.fromRunnable(() -> log.warn("The message {} carries no status", routingKey)))
                .flatMap(status -> flag.get().valueOfWord(status)
                    .map(value -> changeDetector(detector, state -> state.withFlag(flag.get(), value, now())))
                    .orElseGet(() -> unknownWord(flag.get(), status, routingKey))));
    }

    private Mono<Void> entityConfig(final Detector detector, final String routingKey, final byte[] body) {
        return read(routingKey, body, ConfigPayload.class)
            .mapNotNull(ConfigPayload::device)
            .flatMap(device -> changeDetector(detector,
                state -> state.withDevice(device.model(), device.swVersion(), now())));
    }

    private Mono<Void> selfTest(final Detector detector, final String routingKey, final byte[] body) {
        return read(routingKey, body, SelfTestPayload.class)
            .flatMap(payload -> Mono.justOrEmpty(payload.problem())
                .switchIfEmpty(Mono.fromRunnable(() -> log.warn("The message {} carries no problem", routingKey)))
                .flatMap(problem -> changeDetector(detector,
                    state -> state.withFlag(DetectorFlag.SELF_TEST_PROBLEM, problem, now()))));
    }

    //plain text: online, or offline - its own word or the last will the broker publishes when it drops
    private Mono<Void> baseAvailability(final String stationSerial, final byte[] body) {
        final String word = new String(body, StandardCharsets.UTF_8).trim();
        if (!"online".equals(word) && !"offline".equals(word)) {
            log.warn("The base station {} says it is {} - neither online nor offline", stationSerial, word);
            return Mono.empty();
        }
        return changeBase(stationSerial, state -> state.withOnline("online".equals(word), now()));
    }

    private Mono<Void> baseNetwork(final String stationSerial, final String routingKey, final byte[] body) {
        return read(routingKey, body, NetworkPayload.class)
            .flatMap(payload -> changeBase(stationSerial,
                state -> state.withNetwork(payload.ipAddress(), payload.wifiSsid(), payload.wifiRssi(), now())));
    }

    private Mono<Void> changeDetector(final Detector detector, final UnaryOperator<DetectorState> change) {
        return Mono.fromRunnable(() -> states.changeDetector(detector.id(), id -> DetectorState.unknown(id, now()), change)
            .ifPresent(writer::submit));
    }

    private Mono<Void> changeBase(final String stationSerial, final UnaryOperator<BaseState> change) {
        return Mono.fromRunnable(() -> states.changeBase(stationSerial, serial -> BaseState.unknown(serial, now()), change)
            .ifPresent(writer::submit));
    }

    private Mono<Void> unknownWord(final DetectorFlag flag, final String word, final String routingKey) {
        return Mono.fromRunnable(() -> {
            if (unknownWords.add(flag + ":" + word)) {
                log.warn("The word {} of {} is neither {} nor {} - the state is left as it was",
                    word, routingKey, flag.getOn(), flag.getOff());
            }
        });
    }

    //a body that is not the model is a failed message: a WARN, nothing changed, the message acknowledged
    //an empty body (a discovery removed, a retained message cleared) says nothing and is dropped without a word
    private <T> Mono<T> read(final String routingKey, final byte[] body, final Class<T> type) {
        return Mono.justOrEmpty(body)
            .filter(bytes -> bytes.length > 0)
            .map(bytes -> jsonMapper.readValue(bytes, type))
            .onErrorResume(failure -> {
                log.warn("The message {} is not what its topic carries: {}", routingKey, failure.getMessage());
                return Mono.empty();
            });
    }

    private Instant now() {
        return clock.instant();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StatusPayload(String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConfigPayload(DeviceBlock device) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DeviceBlock(String model, @JsonProperty("sw_version") String swVersion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SelfTestPayload(Boolean problem) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NetworkPayload(
        @JsonProperty("ip_address") String ipAddress,
        @JsonProperty("wifi_ssid") String wifiSsid,
        @JsonProperty("wifi_rssi") Integer wifiRssi
    ) {
    }
}
