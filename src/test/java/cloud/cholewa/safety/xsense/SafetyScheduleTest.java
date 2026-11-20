package cloud.cholewa.safety.xsense;

import cloud.cholewa.safety.registry.DetectorRegistry;
import cloud.cholewa.safety.registry.RegistryCallException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SafetyScheduleTest {

    private final DetectorRegistry registry = mock(DetectorRegistry.class);
    private final BirthMessage birthMessage = mock(BirthMessage.class);
    private final SafetySchedule schedule = new SafetySchedule(registry, birthMessage);

    //the base is asked to republish once the registry is known - its messages are dropped before - and only until the
    //broker took one birth message
    @Test
    void should_send_the_birth_message_once_the_registry_is_known_until_one_is_taken() {
        when(registry.refresh()).thenReturn(Mono.just(true), Mono.just(false), Mono.just(false));
        when(registry.isKnown()).thenReturn(true);
        when(birthMessage.send()).thenReturn(Mono.just(false), Mono.just(true));

        //Spring subscribes to the one Mono again for every run
        final Mono<Void> pass = schedule.refreshRegistry();
        pass.as(StepVerifier::create).verifyComplete();
        pass.as(StepVerifier::create).verifyComplete();
        pass.as(StepVerifier::create).verifyComplete();

        verify(registry, times(3)).refresh();
        //the first send failed, the second was taken, the third pass sends none
        verify(birthMessage, times(2)).send();
    }

    @Test
    void should_send_no_birth_message_while_the_registry_was_never_read() {
        when(registry.refresh()).thenReturn(Mono.error(new RegistryCallException("down")));
        when(registry.isKnown()).thenReturn(false);

        schedule.refreshRegistry().as(StepVerifier::create).verifyComplete();

        verify(birthMessage, never()).send();
    }

    //a failed read after a first one keeps the registry known: the birth message still goes out until taken
    @Test
    void should_send_the_birth_message_when_a_later_read_fails() {
        when(registry.refresh()).thenReturn(Mono.error(new RegistryCallException("down")));
        when(registry.isKnown()).thenReturn(true);
        when(birthMessage.send()).thenReturn(Mono.just(true));

        schedule.refreshRegistry().as(StepVerifier::create).verifyComplete();

        verify(birthMessage).send();
    }

    @Test
    void should_ask_the_base_again_with_every_resync() {
        when(birthMessage.send()).thenReturn(Mono.just(true));

        final Mono<Void> pass = schedule.resyncBase();
        pass.as(StepVerifier::create).verifyComplete();
        pass.as(StepVerifier::create).verifyComplete();

        verify(birthMessage, times(2)).send();
    }
}
