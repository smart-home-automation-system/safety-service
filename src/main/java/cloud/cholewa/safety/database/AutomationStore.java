package cloud.cholewa.safety.database;

import cloud.cholewa.safety.automation.AutomationSwitches;
import cloud.cholewa.safety.automation.Reaction;
import cloud.cholewa.safety.automation.ReactionOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * The switches of the reactions to a fire alarm in {@code safety_automation} (HAS-468, one row) and the reactions sent
 * in {@code safety_reaction_run}. The callers bound every statement.
 */
@Component
@RequiredArgsConstructor
public class AutomationStore {

    private static final String READ = "SELECT lights_on, blinds_up, aircon_off FROM safety_automation WHERE id = 1";

    private static final String WRITE = """
        UPDATE safety_automation
        SET lights_on = :lightsOn, blinds_up = :blindsUp, aircon_off = :airconOff, updated_at = :at
        WHERE id = 1
        """;

    private static final String INSERT_RUN = """
        INSERT INTO safety_reaction_run (episode, reaction, target, sent_at, outcome)
        VALUES (:episode, :reaction, :target, :sentAt, :outcome)
        """;

    private static final String RUNS = """
        SELECT episode, reaction, target, sent_at, outcome FROM safety_reaction_run
        WHERE sent_at >= :from AND sent_at < :to
        ORDER BY sent_at DESC, id DESC
        """;

    private final DatabaseClient databaseClient;

    public Mono<AutomationSwitches> read() {
        return databaseClient.sql(READ)
            .map(row -> new AutomationSwitches(
                Boolean.TRUE.equals(row.get("lights_on", Boolean.class)),
                Boolean.TRUE.equals(row.get("blinds_up", Boolean.class)),
                Boolean.TRUE.equals(row.get("aircon_off", Boolean.class))))
            .one();
    }

    public Mono<Void> write(final AutomationSwitches switches, final Instant at) {
        return databaseClient.sql(WRITE)
            .bind("lightsOn", switches.lightsOn())
            .bind("blindsUp", switches.blindsUp())
            .bind("airconOff", switches.airconOff())
            .bind("at", at)
            .fetch()
            .rowsUpdated()
            .then();
    }

    /** A reaction sent; {@code episode} and {@code target} null when not known. */
    public Mono<Void> insertRun(final Long episode, final Reaction reaction, final String target, final Instant sentAt,
                                final ReactionOutcome outcome) {
        final DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(INSERT_RUN)
            .bind("reaction", reaction.getValue())
            .bind("sentAt", sentAt)
            .bind("outcome", outcome.getValue());
        return nullable(nullable(spec, "episode", episode, Long.class), "target", target, String.class)
            .fetch()
            .rowsUpdated()
            .then();
    }

    private static <T> DatabaseClient.GenericExecuteSpec nullable(final DatabaseClient.GenericExecuteSpec spec,
                                                                  final String name, final T value,
                                                                  final Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    /** The reactions sent in a period, the newest first. */
    public Flux<RunRow> runs(final Instant from, final Instant to) {
        return databaseClient.sql(RUNS)
            .bind("from", from)
            .bind("to", to)
            .map(row -> new RunRow(
                row.get("episode", Long.class),
                row.get("reaction", String.class),
                row.get("target", String.class),
                row.get("sent_at", Instant.class),
                row.get("outcome", String.class)))
            .all();
    }

    /** One reaction as stored. */
    public record RunRow(Long episode, String reaction, String target, Instant sentAt, String outcome) {
    }
}
