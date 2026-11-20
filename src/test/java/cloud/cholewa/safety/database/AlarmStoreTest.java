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

//detector_alarm of V2 in a PostgreSQL in Docker: one open episode per detector (the partial unique index), a second open
//answers the one open, a close only of what is open, the marks, the reads by period. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import(AlarmStore.class)
class AlarmStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private AlarmStore store;
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
        databaseClient.sql("DELETE FROM detector_alarm").fetch().rowsUpdated().as(StepVerifier::create)
            .expectNextCount(1).verifyComplete();
    }

    //a retry after a timeout may find the row its first attempt wrote: it answers that one, never a second
    @Test
    void should_keep_one_open_episode_per_detector() {
        final Long first = store.open(151L, "kitchen", NOW).block();

        store.open(151L, "kitchen", NOW.plusSeconds(5)).as(StepVerifier::create)
            .assertNext(again -> assertThat(again).isEqualTo(first))
            .verifyComplete();
        store.open(152L, null, NOW).as(StepVerifier::create)
            .assertNext(other -> assertThat(other).isNotEqualTo(first))
            .verifyComplete();
        store.openDetectors().collectList().as(StepVerifier::create)
            .assertNext(open -> assertThat(open).containsExactlyInAnyOrder(151L, 152L))
            .verifyComplete();
    }

    @Test
    void should_close_only_what_is_open_and_open_anew_after_it() {
        final Long first = store.open(151L, "kitchen", NOW).block();

        store.close(151L, NOW.plusSeconds(60)).as(StepVerifier::create)
            .assertNext(closed -> assertThat(closed).isEqualTo(first))
            .verifyComplete();
        store.close(151L, NOW.plusSeconds(120)).as(StepVerifier::create).verifyComplete();
        store.open(151L, "kitchen", NOW.plusSeconds(180)).as(StepVerifier::create)
            .assertNext(next -> assertThat(next).isNotEqualTo(first))
            .verifyComplete();
    }

    //every episode that overlaps the period, the newest first; an open one without its end, the marks as stored
    @Test
    void should_read_the_episodes_of_a_period_with_their_marks() {
        final Long old = store.open(150L, "living room", NOW.minusSeconds(7200)).block();
        store.close(150L, NOW.minusSeconds(6000)).block();
        final Long before = store.open(151L, "kitchen", NOW.minusSeconds(3600)).block();
        store.markAlarmTold(before, NOW.minusSeconds(3599)).block();
        store.close(151L, NOW.plusSeconds(60)).block();
        store.markOverTold(before, NOW.plusSeconds(61)).block();
        final Long open = store.open(152L, "cinema", NOW.plusSeconds(600)).block();

        store.inPeriod(NOW, NOW.plusSeconds(3600)).collectList().as(StepVerifier::create)
            .assertNext(episodes -> {
                assertThat(episodes).extracting(AlarmStore.AlarmEpisode::id).containsExactly(open, before);
                assertThat(episodes.get(0).clearedAt()).isNull();
                assertThat(episodes.get(0).alarmToldAt()).isNull();
                assertThat(episodes.get(1).room()).isEqualTo("kitchen");
                assertThat(episodes.get(1).startedAt()).isEqualTo(NOW.minusSeconds(3600));
                assertThat(episodes.get(1).clearedAt()).isEqualTo(NOW.plusSeconds(60));
                assertThat(episodes.get(1).alarmToldAt()).isEqualTo(NOW.minusSeconds(3599));
                assertThat(episodes.get(1).overToldAt()).isEqualTo(NOW.plusSeconds(61));
            })
            .verifyComplete();
        assertThat(old).isNotNull();
    }
}
