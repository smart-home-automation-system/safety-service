package cloud.cholewa.safety.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How often the service reads the detectors of the registry again ({@code registry.refresh-every}, 5 min) and sends the
 * birth message of Home Assistant again ({@code base.resync-every}, 10 min since HAS-463) - a base station republishes the
 * state of its detectors, so a base that says nothing for {@code base.silent-after} (30 min, longer than the resync - the
 * base publishes on a change only) is silent; the technical monitor reminds of an open alert every
 * {@code monitor.reminder-every} (1 h). ISO-8601 in the configuration: @Scheduled reads the same text as this record binds, so both read a
 * number without a unit as milliseconds - and the lower bound refuses the start with one, never a pass in a loop.
 */
@Validated
@ConfigurationProperties("safety")
public record SafetyProperties(@NotNull @Valid Registry registry, @NotNull @Valid Base base, @NotNull @Valid Monitor monitor) {

    public record Registry(
        @NotNull @DurationMin(minutes = 1) @DurationMax(hours = 1) Duration refreshEvery
    ) {
    }

    public record Base(
        @NotNull @DurationMin(minutes = 10) @DurationMax(hours = 24) Duration resyncEvery,
        @NotNull @DurationMin(minutes = 15) @DurationMax(hours = 24) Duration silentAfter
    ) {

        //a base asked every resync answers at least that often: a silence shorter than it is the normal state, never an alert
        @AssertTrue(message = "silent-after must be longer than resync-every")
        public boolean isSilentAfterLongerThanResync() {
            return resyncEvery == null || silentAfter == null || silentAfter.compareTo(resyncEvery) > 0;
        }
    }

    public record Monitor(
        @NotNull @DurationMin(minutes = 10) @DurationMax(hours = 24) Duration reminderEvery
    ) {
    }
}
