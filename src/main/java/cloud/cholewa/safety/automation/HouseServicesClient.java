package cloud.cholewa.safety.automation;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.safety.config.InternalServicesProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

/**
 * The calls of the reactions to a fire alarm (HAS-468), each with {@code source=safety}: the lights of a room on
 * (light-service), the blinds of a room up (blinds-service), the air conditioners that are on and one of them off
 * (aircon-service). A command answers what it came to and never fails: 2xx is sent, 404 none (a room without lights or
 * blinds, a unit gone), anything else failed - logged by its status alone, never the body.
 */
@Slf4j
@Component
public class HouseServicesClient {

    private static final Map<String, Boolean> POWER_OFF = Map.of("power", false);

    private final WebClient webClient;
    private final InternalServicesProperties services;

    public HouseServicesClient(@Qualifier("houseServicesWebClient") final WebClient webClient,
                               final InternalServicesProperties services) {
        this.webClient = webClient;
        this.services = services;
    }

    public Mono<ReactionOutcome> lightsOn(final RoomName room) {
        return command("lights of " + room.getValue(), webClient.post()
            .uri(services.light().toString() + "/home/light/rooms/{room}?turn=on&source=safety", room.getValue()));
    }

    public Mono<ReactionOutcome> blindsUp(final RoomName room) {
        return command("blinds of " + room.getValue(), webClient.post()
            .uri(services.blinds().toString() + "/home/blinds/rooms/{room}?command=open&source=safety",
                room.getValue()));
    }

    public Mono<ReactionOutcome> airconOff(final long id) {
        final WebClient.RequestHeadersSpec<?> request = webClient.post()
            .uri(services.aircon().toString() + "/home/aircon/{id}?source=safety", id)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(POWER_OFF);
        return command("air conditioner " + id, request);
    }

    /** The ids of the air conditioners known to be on; fails when aircon-service does not answer the list. */
    public Flux<Long> airconUnitsOn() {
        return webClient.get()
            .uri(URI.create(services.aircon().toString() + "/home/aircon"))
            .retrieve()
            .bodyToFlux(AirconUnit.class)
            .filter(unit -> Boolean.TRUE.equals(unit.power()))
            .map(AirconUnit::id);
    }

    private static Mono<ReactionOutcome> command(final String what, final WebClient.RequestHeadersSpec<?> request) {
        return request.exchangeToMono(response -> response.releaseBody().thenReturn(outcomeOf(what, response)))
            .onErrorResume(failure -> {
                log.warn("The reaction to the fire alarm on the {} failed: {}", what, failure.toString());
                return Mono.just(ReactionOutcome.FAILED);
            });
    }

    private static ReactionOutcome outcomeOf(final String what, final ClientResponse response) {
        if (response.statusCode().is2xxSuccessful()) {
            return ReactionOutcome.SENT;
        }
        if (response.statusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
            log.info("The reaction to the fire alarm found nothing to do on the {} (404)", what);
            return ReactionOutcome.NONE;
        }
        log.warn("The reaction to the fire alarm on the {} was answered {}", what, response.statusCode().value());
        return ReactionOutcome.FAILED;
    }

    //an air conditioner of GET /home/aircon - the rest of its fields are not read
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AirconUnit(long id, Boolean power) {
    }
}
