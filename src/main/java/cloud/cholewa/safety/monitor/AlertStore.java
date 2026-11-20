package cloud.cholewa.safety.monitor;

import cloud.cholewa.safety.monitor.AlertEvaluator.Alert;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

//The episodes of the technical monitor (HAS-463, V3), a statement each; one open episode per item (the partial unique
//index), the closed ones kept
@Repository
@RequiredArgsConstructor
public class AlertStore {

    private static final String ITEM = "item";
    private static final String AT = "at";

    private final DatabaseClient databaseClient;

    /** The open episodes; a kind this version does not know (a later release wrote it) is left out. */
    public Flux<Alert> open() {
        return databaseClient.sql("""
                SELECT kind, device, name, opened_at, told, last_reminded_at FROM safety_alert
                WHERE closed_at IS NULL ORDER BY opened_at, id
                """)
            .map(row -> AlertKind.of(row.get("kind", String.class))
                .map(kind -> new Alert(kind,
                    row.get("device", String.class),
                    row.get("name", String.class),
                    row.get("opened_at", Instant.class),
                    Boolean.TRUE.equals(row.get("told", Boolean.class)),
                    row.get("last_reminded_at", Instant.class))))
            .all()
            .flatMap(Mono::justOrEmpty);
    }

    //written when the alert is due, before it is told: an alert that never gets through is still announced with the return
    public Mono<Void> due(final Alert alert) {
        return databaseClient.sql("""
                INSERT INTO safety_alert (item, kind, device, name, opened_at, told)
                VALUES (:item, :kind, :device, :name, :openedAt, FALSE)
                ON CONFLICT (item) WHERE closed_at IS NULL DO NOTHING
                """)
            .bind(ITEM, alert.item())
            .bind("kind", alert.kind().getValue())
            .bind("device", alert.device())
            .bind("name", alert.name())
            .bind("openedAt", alert.since())
            .fetch().rowsUpdated().then();
    }

    //the alert or a reminder got through
    public Mono<Void> told(final String item, final Instant at) {
        return databaseClient.sql(
                "UPDATE safety_alert SET told = TRUE, last_reminded_at = :at WHERE item = :item AND closed_at IS NULL")
            .bind(AT, at)
            .bind(ITEM, item)
            .fetch().rowsUpdated().then();
    }

    //the return told, or the device no longer watched
    public Mono<Void> close(final String item, final Instant at) {
        return databaseClient.sql("UPDATE safety_alert SET closed_at = :at WHERE item = :item AND closed_at IS NULL")
            .bind(AT, at)
            .bind(ITEM, item)
            .fetch().rowsUpdated().then();
    }
}
