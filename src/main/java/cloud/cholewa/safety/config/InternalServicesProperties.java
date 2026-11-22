package cloud.cholewa.safety.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

/**
 * The services this one calls inside the cluster, by k8s DNS. {@code database} - database-service: the fire detectors of
 * the device registry; {@code light}, {@code blinds}, {@code aircon} - the reactions to a fire alarm (HAS-468). No
 * default: a made-up address would start the service and fail every call.
 */
@Validated
@ConfigurationProperties("internal.service")
public record InternalServicesProperties(@NotNull URI database, @NotNull URI light, @NotNull URI blinds,
                                         @NotNull URI aircon) {
}
