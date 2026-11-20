package cloud.cholewa.safety.monitor;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.safety.config.SafetyProperties;
import cloud.cholewa.safety.detector.SafetyStates;
import cloud.cholewa.safety.error.SafetyException;
import cloud.cholewa.safety.registry.DetectorRegistry;
import cloud.cholewa.safety.registry.DeviceRegistryClient;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

//GET /status refuses as every read of the service until the registry was read once (HAS-463) - a registry of its own,
//never the one a context test shares between its tests
class StatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T18:00:00Z");

    @Test
    void should_refuse_before_the_registry_is_read() {
        final DetectorRegistry registry = new DetectorRegistry(mock(DeviceRegistryClient.class));
        final SafetyStates states = new SafetyStates();
        final AlertStore alertStore = mock(AlertStore.class);
        final Clock clock = Clock.fixed(NOW, ZoneId.of("Europe/Warsaw"));
        final TechnicalMonitor monitor = new TechnicalMonitor(registry, states, alertStore,
            mock(NotificationPublisher.class), new SafetyProperties(new SafetyProperties.Registry(Duration.ofMinutes(5)),
            new SafetyProperties.Base(Duration.ofMinutes(10), Duration.ofMinutes(30)),
            new SafetyProperties.Monitor(Duration.ofHours(1))), clock);
        final StatusService sut = new StatusService(registry, states, monitor, alertStore, clock);

        sut.status().as(StepVerifier::create).verifyError(SafetyException.RegistryUnavailable.class);

        verifyNoInteractions(alertStore);
    }
}
