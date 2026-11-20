package cloud.cholewa.safety;

import cloud.cholewa.commons.database.DatabaseProperties;
import cloud.cholewa.safety.config.SafetyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SafetyServiceApplicationTest {

    @Autowired
    private DatabaseProperties databaseProperties;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private SafetyProperties safetyProperties;

    @Test
    void contextLoads() {
    }

    //the pool is this service's share of the 47 connections of the managed database, counted twice during a
    //rollout; a lost or mistyped key falls back to the library default without a sound
    @Test
    void should_keep_the_connection_pool_at_its_share_of_the_database_budget() {
        assertThat(databaseProperties.pool().maxSize()).isEqualTo(2);
    }

    //a context of a test must not read the registry of the house nor send the birth message to the broker
    @Test
    void should_schedule_nothing_under_the_test_profile() {
        assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    //the scheduler of cholewa-commons >= 1.11.0 (HAS-300)
    @Test
    void should_give_the_scheduled_tasks_a_pool() {
        assertThat(applicationContext.getEnvironment().getProperty("spring.task.scheduling.pool.size", Integer.class))
            .isEqualTo(10);
    }

    //the passwords of the database, of the broker and of the notification connection (HAS-461) have no default: the start
    //is refused without any of them (cholewa.required-properties)
    @Test
    void should_require_the_passwords_of_the_database_and_of_the_broker() {
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.required-properties[0].property"))
            .isEqualTo("database.password");
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.required-properties[0].variable"))
            .isEqualTo("database-password");
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.required-properties[1].property"))
            .isEqualTo("spring.rabbitmq.password");
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.required-properties[1].variable"))
            .isEqualTo("rabbitmq-password");
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.required-properties[2].property"))
            .isEqualTo("notification.password");
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.required-properties[2].variable"))
            .isEqualTo("notification-rabbitmq-password");
        assertThat(applicationContext.getEnvironment().getProperty("cholewa.required-properties[3].property")).isNull();
    }

    //the events of the base: the queue of every device, the user device of /device, manual acks
    @Test
    void should_consume_the_device_queue_as_the_device_user_with_manual_acks() {
        assertThat(applicationContext.getEnvironment().getProperty("spring.rabbitmq.username")).isEqualTo("device");
        assertThat(applicationContext.getEnvironment().getProperty("spring.rabbitmq.virtual-host")).isEqualTo("/device");
        assertThat(applicationContext.getEnvironment().getProperty("spring.rabbitmq.listener.simple.acknowledge-mode"))
            .isEqualTo("manual");
    }

    //the registry read every 5 min, the base asked every hour - bound in minutes, never milliseconds
    @Test
    void should_read_the_registry_every_five_minutes_and_ask_the_base_every_hour() {
        assertThat(safetyProperties.registry().refreshEvery()).isEqualTo(Duration.ofMinutes(5));
        assertThat(safetyProperties.base().resyncEvery()).isEqualTo(Duration.ofHours(1));
    }
}
