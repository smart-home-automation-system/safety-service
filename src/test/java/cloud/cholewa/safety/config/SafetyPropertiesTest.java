package cloud.cholewa.safety.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

//@Scheduled reads the same text as the record binds: a number without a unit is milliseconds for both, and the bounds
//refuse the start with one - never a read of the registry or a birth message in a loop
class SafetyPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void should_accept_the_delays_of_the_configuration() {
        assertThat(validator.validate(properties(Duration.ofMinutes(5), Duration.ofMinutes(10)))).isEmpty();
    }

    //HAS-463: a base answers the resync - a silence no longer than it is the normal state, refused at the start
    @Test
    void should_refuse_a_silence_not_longer_than_the_resync() {
        assertThat(validator.validate(new SafetyProperties(new SafetyProperties.Registry(Duration.ofMinutes(5)),
            new SafetyProperties.Base(Duration.ofMinutes(30), Duration.ofMinutes(30)),
            new SafetyProperties.Monitor(Duration.ofHours(1))))).hasSize(1);
        assertThat(validator.validate(new SafetyProperties(new SafetyProperties.Registry(Duration.ofMinutes(5)),
            new SafetyProperties.Base(Duration.ofMinutes(10), Duration.ofMinutes(30)),
            new SafetyProperties.Monitor(Duration.ofMillis(60))))).hasSize(1);
    }

    @Test
    void should_refuse_a_delay_of_milliseconds() {
        assertThat(validator.validate(properties(Duration.ofMillis(5), Duration.ofMinutes(10)))).hasSize(1);
        assertThat(validator.validate(properties(Duration.ofMinutes(5), Duration.ofMillis(60)))).hasSize(1);
    }

    @Test
    void should_refuse_a_delay_above_its_bound() {
        assertThat(validator.validate(properties(Duration.ofHours(2), Duration.ofMinutes(10)))).hasSize(1);
        assertThat(validator.validate(properties(Duration.ofMinutes(5), Duration.ofDays(2)))).isNotEmpty();
    }

    private static SafetyProperties properties(final Duration refreshEvery, final Duration resyncEvery) {
        return new SafetyProperties(new SafetyProperties.Registry(refreshEvery),
            new SafetyProperties.Base(resyncEvery, Duration.ofMinutes(30)), new SafetyProperties.Monitor(Duration.ofHours(1)));
    }
}
