package cloud.cholewa.safety.detector;

import cloud.cholewa.safety.api.model.AlarmReply;
import cloud.cholewa.safety.database.AlarmStore;
import cloud.cholewa.safety.error.SafetyException;
import cloud.cholewa.safety.registry.Detector;
import cloud.cholewa.safety.registry.DetectorRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.Optional;

/**
 * The fire alarm episodes of a period (HAS-461): every episode that overlaps it, the newest first, an open one without
 * {@code clearedAt}. The period is the wall time of the house, at most {@link #MAX_PERIOD}; the name of the detector is
 * the registry's now, left out for a detector it no longer lists.
 */
@Service
@RequiredArgsConstructor
public class AlarmService {

    static final Period MAX_PERIOD = Period.ofDays(30);

    private final AlarmStore store;
    private final DetectorRegistry registry;
    private final Clock clock;

    public Flux<AlarmReply> alarms(final LocalDateTime from, final LocalDateTime to) {
        return Mono.justOrEmpty(problemOf(from, to))
            .flatMap(problem -> Mono.<Void>error(new SafetyException.InvalidRange(problem)))
            .thenMany(Flux.defer(() -> store.inPeriod(instant(from), instant(to))))
            .map(this::reply);
    }

    //from included, to not; the wall time of the house plus the days - no duration between two local times
    public static Optional<String> problemOf(final LocalDateTime from, final LocalDateTime to) {
        if (!from.isBefore(to)) {
            return Optional.of("from must be before to");
        }
        if (to.isAfter(from.plus(MAX_PERIOD))) {
            return Optional.of("The period may be at most " + MAX_PERIOD.getDays() + " days");
        }
        return Optional.empty();
    }

    private AlarmReply reply(final AlarmStore.AlarmEpisode episode) {
        return new AlarmReply(
            episode.id(),
            episode.detectorId(),
            registry.find(episode.detectorId()).map(Detector::name).orElse(null),
            episode.room(),
            house(episode.startedAt()),
            house(episode.clearedAt()),
            house(episode.alarmToldAt()),
            house(episode.overToldAt()));
    }

    private Instant instant(final LocalDateTime houseTime) {
        return houseTime.atZone(clock.getZone()).toInstant();
    }

    private LocalDateTime house(final Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, clock.getZone());
    }
}
