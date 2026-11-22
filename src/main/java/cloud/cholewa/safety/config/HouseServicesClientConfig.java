package cloud.cholewa.safety.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

//The client of light-service, blinds-service and aircon-service for the reactions to a fire alarm (HAS-468): one pool,
//no base address - every call names its service. Built from the injected builder (traced, logged by Logbook); a call
//waits RESPONSE_TIMEOUT at most - a room of blinds or air conditioners answers once every unit took its command
@Configuration
public class HouseServicesClientConfig {

    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(20);
    static final int CONNECT_TIMEOUT_MILLIS = 5000;

    //the lesson of the gateway (HAS-212): an idle connection is never kept for ever
    static final Duration MAX_IDLE_TIME = Duration.ofMinutes(2);
    static final Duration MAX_LIFE_TIME = Duration.ofMinutes(30);

    @Bean(destroyMethod = "dispose")
    ConnectionProvider houseServicesConnectionProvider() {
        return ConnectionProvider.builder("house-services")
            .maxIdleTime(MAX_IDLE_TIME)
            .maxLifeTime(MAX_LIFE_TIME)
            .build();
    }

    @Bean
    WebClient houseServicesWebClient(final WebClient.Builder webClientBuilder,
                                     final ConnectionProvider houseServicesConnectionProvider) {
        final HttpClient httpClient = HttpClient.create(houseServicesConnectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
            .responseTimeout(RESPONSE_TIMEOUT);

        return webClientBuilder.clone()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
