package cloud.cholewa.safety.xsense;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The birth message of Home Assistant: {@code online} on {@code homeassistant/status}, plain text, to {@code amq.topic}
 * of {@code /device} (routing key {@code homeassistant.status}). The base subscribes to that topic and answers by
 * republishing the state of every detector within seconds (seen 2026-11-16 and 2026-11-20 - on 2026-11-20 without its
 * discovery: the model and firmware come only when the base sends its discovery on its own). Persistent, so that the
 * MQTT plugin delivers it with QoS 1. Every device queue gets it too (bound {@code #}); no service reads it.
 * <p>
 * RabbitTemplate blocks while it sends, so the send runs on boundedElastic, bounded; a failure is a WARN - the next
 * pass asks again. The answer says whether the broker took it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BirthMessage {

    static final String EXCHANGE = "amq.topic";
    static final String ROUTING_KEY = "homeassistant.status";
    static final String PAYLOAD = "online";
    //below what is left of the pass of the registry (50 s) after its three reads of 5 s at most
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(20);

    private final RabbitTemplate rabbitTemplate;

    /** Sends the birth message; {@code true} once the broker took it, {@code false} when it did not. */
    public Mono<Boolean> send() {
        return Mono.fromCallable(() -> {
                rabbitTemplate.send(EXCHANGE, ROUTING_KEY, message());
                return true;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .timeout(SEND_TIMEOUT)
            .doOnNext(ignored -> log.info("The birth message asked the X-Sense bases to republish their detectors"))
            .onErrorResume(failure -> {
                log.warn("The birth message was not sent: {}", failure.toString());
                return Mono.just(false);
            });
    }

    private static Message message() {
        final MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return new Message(PAYLOAD.getBytes(StandardCharsets.UTF_8), properties);
    }
}
