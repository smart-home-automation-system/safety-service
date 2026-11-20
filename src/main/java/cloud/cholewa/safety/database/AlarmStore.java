package cloud.cholewa.safety.database;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;

/**
 * The fire alarm episodes of {@code detector_alarm} (HAS-461), PostgreSQL's own statements: an episode opened only when
 * the detector has none open (the partial unique index decides, never a read before the write), closed by the detector,
 * the two marks of what the household was told. Whether the household is told is not decided here but by the episodes in
 * memory ({@code AlarmEpisodes}): a retried write cannot tell an episode it opened itself from one open before.
 * A write is bounded and tried twice more with a backoff, never for a
 * refusal of the schema; what is left is an error for the caller - {@code AlarmEpisodes} tells the household whatever
 * the database said.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlarmStore {

    static final Duration WRITE_TIMEOUT = Duration.ofSeconds(30);
    static final int WRITE_RETRIES = 2;
    static final Duration WRITE_BACKOFF = Duration.ofSeconds(1);

    //the episode opened, or the one open already: a retry after a timeout may find the row its first attempt wrote, and
    //must still answer it (the SELECT does not see the INSERT of the same statement - one snapshot)
    private static final String OPEN = """
        WITH inserted AS (
            INSERT INTO detector_alarm (detector_id, room, started_at)
            VALUES (:detectorId, :room, :startedAt)
            ON CONFLICT (detector_id) WHERE cleared_at IS NULL DO NOTHING
            RETURNING id
        )
        SELECT id FROM inserted
        UNION ALL
        SELECT id FROM detector_alarm WHERE detector_id = :detectorId AND cleared_at IS NULL
        LIMIT 1
        """;

    private static final String CLOSE = """
        UPDATE detector_alarm SET cleared_at = :clearedAt
        WHERE detector_id = :detectorId AND cleared_at IS NULL
        RETURNING id
        """;

    private static final String MARK_ALARM_TOLD = "UPDATE detector_alarm SET alarm_told_at = :at WHERE id = :id";
    private static final String MARK_OVER_TOLD = "UPDATE detector_alarm SET over_told_at = :at WHERE id = :id";

    private static final String OPEN_DETECTORS = "SELECT detector_id FROM detector_alarm WHERE cleared_at IS NULL";

    //every episode that overlaps the period - started before its end and not cleared before its start
    private static final String IN_PERIOD = """
        SELECT id, detector_id, room, started_at, cleared_at, alarm_told_at, over_told_at FROM detector_alarm
        WHERE started_at < :to AND (cleared_at IS NULL OR cleared_at >= :from)
        ORDER BY started_at DESC, id DESC
        """;

    private final DatabaseClient databaseClient;

    /** The id of the open episode of the detector: the one opened now, or the one open already. */
    public Mono<Long> open(final long detectorId, final String room, final Instant startedAt) {
        final DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(OPEN)
            .bind("detectorId", detectorId)
            .bind("startedAt", startedAt);
        return bounded((room == null ? spec.bindNull("room", String.class) : spec.bind("room", room))
            .map(row -> row.get("id", Long.class))
            .one());
    }

    /** The id of the episode closed, empty when the detector has none open. */
    public Mono<Long> close(final long detectorId, final Instant clearedAt) {
        return bounded(databaseClient.sql(CLOSE)
            .bind("detectorId", detectorId)
            .bind("clearedAt", clearedAt)
            .map(row -> row.get("id", Long.class))
            .one());
    }

    public Mono<Void> markAlarmTold(final long id, final Instant at) {
        return bounded(databaseClient.sql(MARK_ALARM_TOLD).bind("id", id).bind("at", at).fetch().rowsUpdated()).then();
    }

    public Mono<Void> markOverTold(final long id, final Instant at) {
        return bounded(databaseClient.sql(MARK_OVER_TOLD).bind("id", id).bind("at", at).fetch().rowsUpdated()).then();
    }

    /** The detectors with an open episode - read back at the start. */
    public Flux<Long> openDetectors() {
        return databaseClient.sql(OPEN_DETECTORS).map(row -> row.get("detector_id", Long.class)).all();
    }

    /** Every episode that overlaps {@code from} (included) - {@code to} (not), the newest first. */
    public Flux<AlarmEpisode> inPeriod(final Instant from, final Instant to) {
        return databaseClient.sql(IN_PERIOD)
            .bind("from", from)
            .bind("to", to)
            .map(row -> new AlarmEpisode(
                row.get("id", Long.class),
                row.get("detector_id", Long.class),
                row.get("room", String.class),
                row.get("started_at", Instant.class),
                row.get("cleared_at", Instant.class),
                row.get("alarm_told_at", Instant.class),
                row.get("over_told_at", Instant.class)))
            .all();
    }

    private static <T> Mono<T> bounded(final Mono<T> write) {
        return write
            .timeout(WRITE_TIMEOUT)
            .retryWhen(Retry.backoff(WRITE_RETRIES, WRITE_BACKOFF)
                .filter(failure -> !(failure instanceof NonTransientDataAccessException)));
    }

    /** One episode as stored; {@code clearedAt} null while it lasts, a mark null when it was not told. */
    public record AlarmEpisode(long id, long detectorId, String room, Instant startedAt, Instant clearedAt,
                               Instant alarmToldAt, Instant overToldAt) {
    }
}
