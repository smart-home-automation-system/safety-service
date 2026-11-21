package cloud.cholewa.safety.database;

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

//safety_command of V4 in a PostgreSQL in Docker: a row per command, its answer marked once by its request id, the last
//ones of a detector the newest first, a command word the table does not know refused. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import(CommandStore.class)
class CommandStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private CommandStore store;
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
        databaseClient.sql("DELETE FROM safety_command").fetch().rowsUpdated().as(StepVerifier::create)
            .expectNextCount(1).verifyComplete();
    }

    @Test
    void should_keep_the_commands_of_a_detector_and_their_answers() {
        store.insert(150L, "mute", "dashboard", "sh-1", NOW).as(StepVerifier::create).verifyComplete();
        store.insert(150L, "self test", "manual", "sh-2", NOW.plusSeconds(120)).as(StepVerifier::create)
            .verifyComplete();
        store.insert(151L, "mute", "dashboard", "sh-3", NOW).as(StepVerifier::create).verifyComplete();
        store.confirm("sh-1", NOW.plusSeconds(4)).as(StepVerifier::create).verifyComplete();
        store.confirm("sh-1", NOW.plusSeconds(30)).as(StepVerifier::create).verifyComplete();

        store.last(150L, 50).collectList().as(StepVerifier::create)
            .assertNext(rows -> assertThat(rows).containsExactly(
                new CommandStore.CommandRow("sh-2", "self test", "manual", NOW.plusSeconds(120), null),
                new CommandStore.CommandRow("sh-1", "mute", "dashboard", NOW, NOW.plusSeconds(4))))
            .verifyComplete();
        store.last(150L, 1).collectList().as(StepVerifier::create)
            .assertNext(rows -> assertThat(rows).extracting(CommandStore.CommandRow::requestId).containsExactly("sh-2"))
            .verifyComplete();
    }

    @Test
    void should_refuse_a_command_the_table_does_not_know() {
        store.insert(150L, "reset", "dashboard", "sh-1", NOW).as(StepVerifier::create).verifyError();
    }
}
