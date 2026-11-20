package cloud.cholewa.safety.monitor;

import cloud.cholewa.safety.api.model.StatusReply;
import cloud.cholewa.safety.detector.BaseState;
import cloud.cholewa.safety.detector.Flag;
import cloud.cholewa.safety.detector.SafetyStates;
import cloud.cholewa.safety.error.SafetyException;
import cloud.cholewa.safety.monitor.AlertEvaluator.Watched;
import cloud.cholewa.safety.registry.DetectorRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@code GET /status} (HAS-463): the bases and the detectors in trouble judged now by the same rules as the technical
 * monitor ({@link TechnicalMonitor#watched}), the open alerts as stored. {@code REGISTRY_UNAVAILABLE} until the registry
 * was read once, as every read of the service.
 */
@Service
@RequiredArgsConstructor
public class StatusService {

    private final DetectorRegistry registry;
    private final SafetyStates states;
    private final TechnicalMonitor monitor;
    private final AlertStore alertStore;
    private final Clock clock;

    public Mono<StatusReply> status() {
        return Mono.fromSupplier(registry::isKnown)
            .filter(Boolean::booleanValue)
            .switchIfEmpty(Mono.error(SafetyException.RegistryUnavailable::new))
            .flatMap(known -> {
                final Instant now = clock.instant();
                final List<Watched> watched = monitor.watched(now);
                return alertStore.open()
                    .map(alert -> new StatusReply.OpenAlert(alert.kind().getValue(), alert.device(), alert.name(),
                        house(alert.since()), alert.told()))
                    .collectList()
                    .map(alerts -> new StatusReply(bases(watched), detectorsInTrouble(watched), alerts));
            });
    }

    private List<StatusReply.Base> bases(final List<Watched> watched) {
        return watched.stream()
            .filter(thing -> thing.kind() == AlertKind.BASE_SILENT)
            .map(silence -> {
                final Optional<BaseState> base = states.base(silence.device());
                return new StatusReply.Base(
                    silence.device(),
                    base.map(BaseState::online).map(Flag::value).orElse(null),
                    lastHeard(silence),
                    base.map(BaseState::ipAddress).orElse(null),
                    base.map(BaseState::wifiRssi).orElse(null),
                    silence.since() != null);
            })
            .toList();
    }

    //the last heard of the base and its detectors - what the monitor judges the silence by
    private LocalDateTime lastHeard(final Watched silence) {
        return house(monitor.lastHeardOf(silence.device()));
    }

    private static int detectorsInTrouble(final List<Watched> watched) {
        return (int) watched.stream()
            .filter(thing -> thing.kind().isDetector() && thing.since() != null)
            .map(Watched::device)
            .distinct()
            .count();
    }

    private LocalDateTime house(final Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, clock.getZone());
    }
}
