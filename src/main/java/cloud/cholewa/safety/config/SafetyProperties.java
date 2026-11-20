package cloud.cholewa.safety.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How often the service reads the detectors of the registry again ({@code registry.refresh-every}, 5 min) and sends the
 * birth message of Home Assistant again ({@code base.resync-every}, 1 h) - a base station restarted since republishes the
 * state of its detectors. ISO-8601 in the configuration: @Scheduled reads the same text as this record binds, so both read a
 * number without a unit as milliseconds - and the lower bound refuses the start with one, never a pass in a loop.
 */
@Validated
@ConfigurationProperties("safety")
public record SafetyProperties(@NotNull @Valid Registry registry, @NotNull @Valid Base base) {

    public record Registry(
        @NotNull @DurationMin(minutes = 1) @DurationMax(hours = 1) Duration refreshEvery
    ) {
    }

    public record Base(
        @NotNull @DurationMin(minutes = 10) @DurationMax(hours = 24) Duration resyncEvery
    ) {
    }
}
