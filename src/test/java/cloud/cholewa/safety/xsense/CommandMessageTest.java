package cloud.cholewa.safety.xsense;

import cloud.cholewa.safety.command.DetectorCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

//the command topic and the payload the base announces in the configs of its buttons, on an invented base
class CommandMessageTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final CommandMessage commandMessage = new CommandMessage(rabbitTemplate, JsonMapper.builder().build());

    @Test
    void should_send_the_command_to_the_topic_of_the_detector() {
        commandMessage.send("SBS50ABCDEF01", "0000000A", "sh-1", DetectorCommand.SELF_TEST)
            .as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq("amq.topic"), eq("xsense.sbs50.SBS50ABCDEF01.device.0000000A.cmd"),
            sent.capture());
        assertThat(new String(sent.getValue().getBody(), StandardCharsets.UTF_8))
            .isEqualTo("{\"requestId\":\"sh-1\",\"cmd\":\"self_test\",\"params\":{}}");
        assertThat(sent.getValue().getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(sent.getValue().getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    @Test
    void should_fail_when_the_broker_is_away() {
        doThrow(new AmqpConnectException(new ConnectException("refused")))
            .when(rabbitTemplate).send(any(String.class), any(String.class), any(Message.class));

        commandMessage.send("SBS50ABCDEF01", "00000001", "sh-1", DetectorCommand.MUTE)
            .as(StepVerifier::create).verifyError(AmqpConnectException.class);
    }
}
