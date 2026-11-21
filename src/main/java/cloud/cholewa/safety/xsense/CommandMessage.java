package cloud.cholewa.safety.xsense;

import cloud.cholewa.safety.command.DetectorCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

/**
 * A command of a detector (HAS-464): the topic {@code xsense/sbs50/<station>/device/<id>/cmd} the base subscribes to
 * (13 bindings of its subscription queue, seen 2026-11-16 and 2026-11-20) - the routing key
 * {@code xsense.sbs50.<station>.device.<id>.cmd} of {@code amq.topic} of {@code /device} - with the JSON the base
 * announces in the configs of its buttons:
 * {@code {"requestId": "sh-<uuid>", "cmd": "mute" | "self_test", "params": {}}}.
 * Persistent, so that the MQTT plugin delivers it with QoS 1; never retained (a plain AMQP publish is not).
 * <p>
 * RabbitTemplate blocks while it sends, so the send runs on boundedElastic, bounded; a failure is an error for the
 * caller. A send that returns proves no more than that the connection wrote it (no publisher confirms, no mandatory
 * returns): the base answers by the state of the detector, and that is the proof.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandMessage {

    static final String EXCHANGE = "amq.topic";
    public static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final RabbitTemplate rabbitTemplate;
    private final JsonMapper jsonMapper;

    public Mono<Void> send(final String stationSerial, final String deviceId, final String requestId,
                           final DetectorCommand command) {
        return Mono.fromRunnable(() -> rabbitTemplate.send(EXCHANGE, routingKey(stationSerial, deviceId),
                message(requestId, command)))
            .subscribeOn(Schedulers.boundedElastic())
            .timeout(SEND_TIMEOUT)
            .then();
    }

    static String routingKey(final String stationSerial, final String deviceId) {
        return "xsense.sbs50." + stationSerial + ".device." + deviceId + ".cmd";
    }

    private Message message(final String requestId, final DetectorCommand command) {
        final MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return new Message(jsonMapper.writeValueAsBytes(new Payload(requestId, command.getCmd(), Map.of())),
            properties);
    }

    //the order of the fields is the order the base announces them in
    record Payload(String requestId, String cmd, Map<String, Object> params) {
    }
}
