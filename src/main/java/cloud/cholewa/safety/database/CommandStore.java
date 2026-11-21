package cloud.cholewa.safety.database;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * The commands of the detectors in {@code safety_command} (HAS-464): a row for each command sent, its
 * answer marked by its request id, the last ones of a detector. The callers bound every statement - a command is sent
 * whether its row is stored or not.
 */
@Component
@RequiredArgsConstructor
public class CommandStore {

    private static final String INSERT = """
        INSERT INTO safety_command (detector_id, command, source, request_id, sent_at)
        VALUES (:detectorId, :command, :source, :requestId, :sentAt)
        """;

    private static final String CONFIRM = """
        UPDATE safety_command SET confirmed_at = :at WHERE request_id = :requestId AND confirmed_at IS NULL
        """;

    private static final String LAST = """
        SELECT request_id, command, source, sent_at, confirmed_at FROM safety_command
        WHERE detector_id = :detectorId
        ORDER BY sent_at DESC, id DESC
        LIMIT :limit
        """;

    private final DatabaseClient databaseClient;

    public Mono<Void> insert(final long detectorId, final String command, final String source, final String requestId,
                             final Instant sentAt) {
        return databaseClient.sql(INSERT)
            .bind("detectorId", detectorId)
            .bind("command", command)
            .bind("source", source)
            .bind("requestId", requestId)
            .bind("sentAt", sentAt)
            .fetch()
            .rowsUpdated()
            .then();
    }

    public Mono<Void> confirm(final String requestId, final Instant at) {
        return databaseClient.sql(CONFIRM).bind("requestId", requestId).bind("at", at).fetch().rowsUpdated().then();
    }

    /** The last {@code limit} commands of the detector, the newest first. */
    public Flux<CommandRow> last(final long detectorId, final int limit) {
        return databaseClient.sql(LAST)
            .bind("detectorId", detectorId)
            .bind("limit", limit)
            .map(row -> new CommandRow(
                row.get("request_id", String.class),
                row.get("command", String.class),
                row.get("source", String.class),
                row.get("sent_at", Instant.class),
                row.get("confirmed_at", Instant.class)))
            .all();
    }

    /** One command as stored; {@code confirmedAt} null while the base has not answered it. */
    public record CommandRow(String requestId, String command, String source, Instant sentAt, Instant confirmedAt) {
    }
}
