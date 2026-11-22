package cloud.cholewa.safety.database;

import cloud.cholewa.safety.automation.AutomationSwitches;
import cloud.cholewa.safety.automation.Reaction;
import cloud.cholewa.safety.automation.ReactionOutcome;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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

//safety_automation of V5 and safety_reaction_run of V6 in a PostgreSQL in Docker: the switches off by default, written
//whole; a run with or without its episode and target, the words pinned by the checks, the reads by period. Without a
//Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import(AutomationStore.class)
class AutomationStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private AutomationStore store;
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
        databaseClient.sql("DELETE FROM safety_reaction_run").fetch().rowsUpdated().as(StepVerifier::create)
            .expectNextCount(1).verifyComplete();
        databaseClient.sql("UPDATE safety_automation SET lights_on = FALSE, blinds_up = FALSE, aircon_off = FALSE")
            .fetch().rowsUpdated().as(StepVerifier::create).expectNextCount(1).verifyComplete();
    }

    @Test
    void should_keep_every_switch_off_by_default_and_write_them_whole() {
        store.read().as(StepVerifier::create)
            .expectNext(new AutomationSwitches(false, false, false))
            .verifyComplete();

        store.write(new AutomationSwitches(true, false, true), NOW).as(StepVerifier::create).verifyComplete();

        store.read().as(StepVerifier::create)
            .expectNext(new AutomationSwitches(true, false, true))
            .verifyComplete();
        databaseClient.sql("SELECT count(*) AS n FROM safety_automation").map(row -> row.get("n", Long.class)).one()
            .as(StepVerifier::create).expectNext(1L).verifyComplete();
    }

    @Test
    void should_store_the_runs_and_read_them_by_period_the_newest_first() {
        store.insertRun(7L, Reaction.LIGHTS_ON, "kitchen", NOW, ReactionOutcome.SENT).block();
        store.insertRun(7L, Reaction.BLINDS_UP, "kitchen", NOW.plusSeconds(1), ReactionOutcome.NONE).block();
        store.insertRun(null, Reaction.AIRCON_OFF, null, NOW.plusSeconds(2), ReactionOutcome.FAILED).block();
        store.insertRun(8L, Reaction.LIGHTS_ON, "hall up", NOW.minusSeconds(3600), ReactionOutcome.SENT).block();

        store.runs(NOW, NOW.plusSeconds(60)).collectList().as(StepVerifier::create)
            .assertNext(runs -> assertThat(runs).containsExactly(
                new AutomationStore.RunRow(null, "airconOff", null, NOW.plusSeconds(2), "failed"),
                new AutomationStore.RunRow(7L, "blindsUp", "kitchen", NOW.plusSeconds(1), "none"),
                new AutomationStore.RunRow(7L, "lightsOn", "kitchen", NOW, "sent")))
            .verifyComplete();
    }

    @Test
    void should_refuse_a_word_the_checks_do_not_know() {
        databaseClient.sql("INSERT INTO safety_reaction_run (reaction, sent_at, outcome) VALUES ('furnaceOff', :at, 'sent')")
            .bind("at", NOW).fetch().rowsUpdated().as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
        databaseClient.sql("INSERT INTO safety_reaction_run (reaction, sent_at, outcome) VALUES ('lightsOn', :at, 'maybe')")
            .bind("at", NOW).fetch().rowsUpdated().as(StepVerifier::create)
            .expectError(DataIntegrityViolationException.class).verify();
    }
}
