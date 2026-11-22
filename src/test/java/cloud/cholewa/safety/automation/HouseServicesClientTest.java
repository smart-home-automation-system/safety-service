package cloud.cholewa.safety.automation;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.safety.config.InternalServicesProperties;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

//the calls of the reactions (HAS-468): the paths and source=safety of each service, a room of the registry encoded,
//2xx sent, 404 none, anything else or no answer failed - never an error
class HouseServicesClientTest {

    private MockWebServer server;
    private HouseServicesClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        final URI address = URI.create("http://localhost:" + server.getPort());
        client = new HouseServicesClient(WebClient.create(),
            new InternalServicesProperties(URI.create("http://localhost:1"), address, address, address));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void should_switch_the_lights_of_a_room_on_for_safety() throws InterruptedException {
        server.enqueue(new MockResponse.Builder().code(202).build());

        client.lightsOn(RoomName.HALL_UP).as(StepVerifier::create).expectNext(ReactionOutcome.SENT).verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/light/rooms/hall%20up");
        assertThat(request.getUrl().queryParameter("turn")).isEqualTo("on");
        assertThat(request.getUrl().queryParameter("source")).isEqualTo("safety");
    }

    @Test
    void should_open_the_blinds_of_a_room_for_safety() throws InterruptedException {
        server.enqueue(new MockResponse.Builder().code(202).body("{\"blinds\":[]}").build());

        client.blindsUp(RoomName.KITCHEN).as(StepVerifier::create).expectNext(ReactionOutcome.SENT).verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/blinds/rooms/kitchen");
        assertThat(request.getUrl().queryParameter("command")).isEqualTo("open");
        assertThat(request.getUrl().queryParameter("source")).isEqualTo("safety");
    }

    @Test
    void should_switch_an_air_conditioner_off_for_safety() throws InterruptedException {
        server.enqueue(new MockResponse.Builder().code(202).build());

        client.airconOff(140).as(StepVerifier::create).expectNext(ReactionOutcome.SENT).verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getUrl().encodedPath()).isEqualTo("/home/aircon/140");
        assertThat(request.getUrl().queryParameter("source")).isEqualTo("safety");
        assertThat(request.getBody().utf8()).isEqualTo("{\"power\":false}");
    }

    //a room without lights or blinds answers 404: nothing to do, not a failure
    @Test
    void should_take_a_404_as_nothing_to_do() {
        server.enqueue(new MockResponse.Builder().code(404).body("{\"errors\":[]}").build());

        client.blindsUp(RoomName.GARAGE).as(StepVerifier::create).expectNext(ReactionOutcome.NONE).verifyComplete();
    }

    @Test
    void should_take_any_other_answer_or_none_as_failed() throws IOException {
        server.enqueue(new MockResponse.Builder().code(503).build());

        client.lightsOn(RoomName.KITCHEN).as(StepVerifier::create).expectNext(ReactionOutcome.FAILED).verifyComplete();

        server.close();
        client.airconOff(140).as(StepVerifier::create).expectNext(ReactionOutcome.FAILED).verifyComplete();
    }

    //only the units known to be on: off or not known is left alone
    @Test
    void should_read_the_air_conditioners_that_are_on() throws InterruptedException {
        server.enqueue(new MockResponse.Builder()
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body("""
                [{"id":140,"name":"Air conditioner","room":"office","power":true,"mode":"cool"},
                 {"id":141,"name":"Air conditioner","room":"livia","power":false},
                 {"id":144,"name":"Air conditioner","room":"sanctum"}]
                """)
            .build());

        client.airconUnitsOn().collectList().as(StepVerifier::create)
            .assertNext(ids -> assertThat(ids).containsExactly(140L))
            .verifyComplete();
        assertThat(server.takeRequest().getUrl().encodedPath()).isEqualTo("/home/aircon");
    }

    @Test
    void should_fail_the_read_of_the_air_conditioners_when_it_fails() {
        server.enqueue(new MockResponse.Builder().code(503).build());

        client.airconUnitsOn().as(StepVerifier::create).expectError().verify();
    }
}
