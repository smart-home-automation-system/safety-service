package cloud.cholewa.safety.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

//Off under the test profile: a @SpringBootTest context would read the registry of the house and send the birth message to the broker
//(the lesson of water-service, HAS-307). Every reactive task is wrapped in ScheduledPass.bounded of
//cholewa-commons, on the scheduler pool of the library
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {
}
