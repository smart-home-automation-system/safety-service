package cloud.cholewa.safety.xsense;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import reactor.test.StepVerifier;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BirthMessageTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final BirthMessage birthMessage = new BirthMessage(rabbitTemplate);

    //the birth message of Home Assistant: online on homeassistant/status, persistent - QoS 1 to the MQTT subscriber
    @Test
    void should_send_online_on_homeassistant_status() {
        birthMessage.send().as(StepVerifier::create).expectNext(true).verifyComplete();

        final ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq("amq.topic"), eq("homeassistant.status"), sent.capture());
        assertThat(new String(sent.getValue().getBody(), StandardCharsets.UTF_8)).isEqualTo("online");
        assertThat(sent.getValue().getMessageProperties().getContentType()).isEqualTo("text/plain");
        assertThat(sent.getValue().getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    //a broker away is a WARN and false - the pass of the registry asks again; it never fails
    @Test
    void should_complete_when_the_broker_is_away() {
        doThrow(new AmqpConnectException(new ConnectException("refused")))
            .when(rabbitTemplate).send(any(String.class), any(String.class), any(Message.class));

        birthMessage.send().as(StepVerifier::create).expectNext(false).verifyComplete();
    }
}
