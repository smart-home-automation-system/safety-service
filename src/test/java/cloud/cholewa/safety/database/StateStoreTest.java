package cloud.cholewa.safety.database;

import cloud.cholewa.safety.detector.BaseState;
import cloud.cholewa.safety.detector.DetectorFlag;
import cloud.cholewa.safety.detector.DetectorState;
import cloud.cholewa.safety.detector.Flag;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//detector_state and base_state of V1 in a PostgreSQL in Docker: one row per thing, written whole, an older snapshot
//never replaces a newer one (the upsert is PostgreSQL's own), read back as not confirmed. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import(StateStore.class)
class StateStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private StateStore store;
    @Autowired
    private DetectorStateRepository detectorStates;
    @Autowired
    private BaseStateRepository baseStates;

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
        detectorStates.deleteAll().then(baseStates.deleteAll()).as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_store_a_detector_whole_and_read_it_back_not_confirmed() {
        final DetectorState state = new DetectorState(150L,
            Map.of(DetectorFlag.ALARM, new Flag(false, NOW), DetectorFlag.SELF_TEST_PROBLEM, new Flag(true, NOW)),
            "XS01-M", "v1.8.0", NOW, NOW, true);

        store.write(state).thenMany(store.detectors())
            .as(StepVerifier::create)
            .assertNext(read -> {
                assertThat(read.detectorId()).isEqualTo(150L);
                assertThat(read.flags()).isEqualTo(state.flags());
                assertThat(read.flag(DetectorFlag.ONLINE)).isNull();
                assertThat(read.model()).isEqualTo("XS01-M");
                assertThat(read.firmware()).isEqualTo("v1.8.0");
                assertThat(read.lastHeardAt()).isEqualTo(NOW);
                assertThat(read.confirmed()).isFalse();
            })
            .verifyComplete();
    }

    //the listener writes with a prefetch of 3: a snapshot taken earlier must not win when it arrives last
    @Test
    void should_never_replace_a_newer_snapshot_of_a_detector() {
        final DetectorState newer = new DetectorState(150L, Map.of(DetectorFlag.ALARM, new Flag(true, NOW.plusSeconds(5))),
            null, null, NOW.plusSeconds(5), NOW.plusSeconds(5), true);
        final DetectorState older = new DetectorState(150L, Map.of(DetectorFlag.ALARM, new Flag(false, NOW)),
            null, null, NOW, NOW, true);

        store.write(newer).then(store.write(older)).thenMany(store.detectors())
            .as(StepVerifier::create)
            .assertNext(read -> assertThat(read.flag(DetectorFlag.ALARM).value()).isTrue())
            .verifyComplete();
    }

    //a start that could not read the rows back knows one flag of a detector: the rest - the model and firmware the base
    //will not send again included - stays what was stored
    @Test
    void should_keep_what_a_newer_snapshot_does_not_know() {
        final DetectorState stored = new DetectorState(150L,
            Map.of(DetectorFlag.ALARM, new Flag(false, NOW), DetectorFlag.ONLINE, new Flag(true, NOW)),
            "XS01-M", "v1.8.0", NOW, NOW, true);
        final DetectorState afterABlindStart = new DetectorState(150L,
            Map.of(DetectorFlag.ONLINE, new Flag(true, NOW.plusSeconds(60))), null, null, NOW.plusSeconds(60),
            NOW.plusSeconds(60), true);

        store.write(stored).then(store.write(afterABlindStart)).thenMany(store.detectors())
            .as(StepVerifier::create)
            .assertNext(read -> {
                assertThat(read.flag(DetectorFlag.ALARM)).isEqualTo(new Flag(false, NOW));
                assertThat(read.flag(DetectorFlag.ONLINE)).isEqualTo(new Flag(true, NOW.plusSeconds(60)));
                assertThat(read.model()).isEqualTo("XS01-M");
                assertThat(read.firmware()).isEqualTo("v1.8.0");
                assertThat(read.lastHeardAt()).isEqualTo(NOW.plusSeconds(60));
            })
            .verifyComplete();
    }

    @Test
    void should_store_a_base_and_replace_it_with_a_newer_snapshot() {
        final BaseState first = BaseState.unknown("SBS50ABCDEF01", NOW).withNetwork("192.0.2.82", "iot", -35, NOW);
        final BaseState second = first.withOnline(false, NOW.plusSeconds(60));

        store.write(first).then(store.write(second)).thenMany(store.bases())
            .as(StepVerifier::create)
            .assertNext(read -> {
                assertThat(read.stationSerial()).isEqualTo("SBS50ABCDEF01");
                assertThat(read.online()).isEqualTo(new Flag(false, NOW.plusSeconds(60)));
                assertThat(read.ipAddress()).isEqualTo("192.0.2.82");
                assertThat(read.wifiSsid()).isEqualTo("iot");
                assertThat(read.wifiRssi()).isEqualTo(-35);
                assertThat(read.confirmed()).isFalse();
            })
            .verifyComplete();
    }
}
