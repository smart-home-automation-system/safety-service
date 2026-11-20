package cloud.cholewa.safety.rabbit;

import cloud.cholewa.safety.xsense.XsenseEventRouter;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceEventConsumerTest {

    private static final String ROUTING_KEY = "xsense.sbs50.SBS50ABCDEF01.availability";

    private final XsenseEventRouter router = mock(XsenseEventRouter.class);
    private final DeviceEventConsumer consumer = new DeviceEventConsumer(router);

    @Test
    void should_hand_every_message_to_the_router_by_its_routing_key() {
        when(router.route(eq(ROUTING_KEY), any())).thenReturn(Mono.empty());

        consumer.consume(message()).as(StepVerifier::create).verifyComplete();

        verify(router).route(eq(ROUTING_KEY), any());
    }

    //a Mono that errors is handed back by the broker at once - a tight loop; logged and acknowledged instead
    @Test
    void should_complete_when_the_router_fails() {
        when(router.route(eq(ROUTING_KEY), any())).thenReturn(Mono.error(new IllegalStateException("broken")));

        consumer.consume(message()).as(StepVerifier::create).verifyComplete();
    }

    private static Message message() {
        final MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(ROUTING_KEY);
        return new Message("online".getBytes(StandardCharsets.UTF_8), properties);
    }
}
