package cloud.cholewa.safety.config;

import cloud.cholewa.commons.error.GlobalErrorExceptionHandler;
import cloud.cholewa.commons.error.processor.DomainExceptionProcessor;
import cloud.cholewa.safety.error.SafetyError;
import cloud.cholewa.safety.error.SafetyException;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.codec.ServerCodecConfigurer;

import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

//The error contract of the organization (cholewa-commons) with one processor per cause of a refused request
@Configuration
public class ExceptionHandlerConfig {

    @Bean
    @Order(-2)
    public GlobalErrorExceptionHandler globalErrorExceptionHandler(
        final ErrorAttributes errorAttributes,
        final WebProperties webProperties,
        final ApplicationContext applicationContext,
        final ServerCodecConfigurer serverCodecConfigurer
    ) {
        return new GlobalErrorExceptionHandler(
            errorAttributes, webProperties.getResources(), applicationContext, serverCodecConfigurer
        ).withCustomErrorProcessor(Map.of(
            SafetyException.RegistryUnavailable.class,
            new DomainExceptionProcessor(SERVICE_UNAVAILABLE, SafetyError.REGISTRY_UNAVAILABLE),
            SafetyException.RoomNotFound.class, new DomainExceptionProcessor(NOT_FOUND, SafetyError.NOT_FOUND_ROOM),
            SafetyException.DetectorNotFound.class,
            new DomainExceptionProcessor(NOT_FOUND, SafetyError.NOT_FOUND_DETECTOR),
            SafetyException.InvalidRange.class, new DomainExceptionProcessor(BAD_REQUEST, SafetyError.INVALID_RANGE)
        ));
    }
}
