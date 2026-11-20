package cloud.cholewa.safety.monitor;

import cloud.cholewa.safety.monitor.AlertEvaluator.Alert;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

//safety_alert of V3 in a PostgreSQL in Docker: one open episode per item, written when due and marked told, closed on the
//return and kept, read back at a start. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import(AlertStore.class)
class AlertStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-20T18:00:00Z");
    private static final Alert BATTERY = new Alert(AlertKind.BATTERY_LOW, "151",
        "the detector Smoke detector in kitchen (151)", NOW.minusSeconds(300), false, null);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private AlertStore store;
    @Autowired
    private DatabaseClient databaseClient;

    @DynamicPropertySource
    static void connectToTheContainer(final DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
            POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
    }

    @BeforeEach
    void clean() {
        databaseClient.sql("DELETE FROM safety_alert").fetch().rowsUpdated().as(StepVerifier::create)
            .expectNextCount(1).verifyComplete();
    }

    //a second due of the same item while it is open writes nothing; told marks it with the moment
    @Test
    void should_keep_one_open_episode_and_mark_it_told() {
        store.due(BATTERY).then(store.due(BATTERY)).then(store.told(BATTERY.item(), NOW))
            .thenMany(store.open()).collectList()
            .as(StepVerifier::create)
            .assertNext(open -> {
                assertThat(open).hasSize(1);
                assertThat(open.getFirst()).isEqualTo(new Alert(AlertKind.BATTERY_LOW, "151",
                    "the detector Smoke detector in kitchen (151)", NOW.minusSeconds(300), true, NOW));
            })
            .verifyComplete();
    }

    //closed on the return and kept; the next episode of the same item is a new row
    @Test
    void should_close_an_episode_and_open_the_next_anew() {
        store.due(BATTERY).then(store.close(BATTERY.item(), NOW)).thenMany(store.open()).collectList()
            .as(StepVerifier::create)
            .assertNext(open -> assertThat(open).isEmpty())
            .verifyComplete();
        store.due(BATTERY).thenMany(store.open()).count().as(StepVerifier::create).expectNext(1L).verifyComplete();
        databaseClient.sql("SELECT count(*) AS n FROM safety_alert").map(row -> row.get("n", Long.class)).one()
            .as(StepVerifier::create).expectNext(2L).verifyComplete();
    }

    //a kind a later release wrote is left out, never read as another
    @Test
    void should_leave_out_a_kind_this_version_does_not_know() {
        databaseClient.sql("""
                INSERT INTO safety_alert (item, kind, device, name, opened_at, told)
                VALUES ('flood:151', 'flood', '151', 'x', now(), FALSE)
                """).fetch().rowsUpdated()
            .then(store.due(BATTERY))
            .thenMany(store.open()).collectList()
            .as(StepVerifier::create)
            .assertNext(open -> assertThat(open).extracting(Alert::item).containsExactly("battery:151"))
            .verifyComplete();
    }
}
