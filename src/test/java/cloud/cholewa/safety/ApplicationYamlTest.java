package cloud.cholewa.safety;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//The document a pod runs with, read as it is written: a context test runs under the test profile, which sets every
//password, and cholewa.required-properties is satisfied by any value - neither sees a password written in place of its
//variable (gate-service 0.2.0 shipped one and could not log in, HAS-448)
class ApplicationYamlTest {

    @Test
    void should_take_every_password_from_its_variable() throws IOException {
        final List<PropertySource<?>> documents =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        final PropertySource<?> shared = documents.getFirst();

        assertThat(shared.getProperty("spring.rabbitmq.password")).hasToString("${rabbitmq-password}");
        assertThat(shared.getProperty("database.password")).hasToString("${database-password}");
        assertThat(shared.getProperty("notification.password")).hasToString("${notification-rabbitmq-password}");
    }

    //the local run takes the next free pair of ports of the organization, the cluster the pair of every service
    @Test
    void should_listen_on_6200_in_the_cluster_and_6017_locally() throws IOException {
        final List<PropertySource<?>> documents =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));

        assertThat(documents.getFirst().getProperty("server.port")).hasToString("6200");
        assertThat(documents.getFirst().getProperty("management.server.port")).hasToString("8200");
        assertThat(documents.get(1).getProperty("server.port")).hasToString("6017");
        assertThat(documents.get(1).getProperty("management.server.port")).hasToString("8017");
        assertThat(documents.getFirst().getProperty("rabbitmq.queue.name")).hasToString("device.prod.safety");
        assertThat(documents.get(1).getProperty("rabbitmq.queue.name")).hasToString("device.dev.safety");
    }
}
