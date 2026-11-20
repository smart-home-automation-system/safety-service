package cloud.cholewa.safety.xsense;

import cloud.cholewa.safety.registry.DetectorKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

//the routing keys as the base published them (2026-11-16, 2026-11-20), with an invented serial
class XsenseTopicTest {

    private static final DetectorKey DETECTOR = new DetectorKey("SBS50ABCDEF01", "0000000B");

    @Test
    void should_read_a_flag_of_a_detector() {
        assertThat(XsenseTopic.parse(
            "homeassistant.binary_sensor.SBS50ABCDEF01_0000000B.SBS50ABCDEF01_0000000B_smokealarm.state"))
            .contains(new XsenseTopic.EntityState(DETECTOR, "smokealarm"));
    }

    @Test
    void should_read_the_discovery_of_a_detector() {
        assertThat(XsenseTopic.parse(
            "homeassistant.binary_sensor.SBS50ABCDEF01_0000000B.SBS50ABCDEF01_0000000B_test_problem.config"))
            .contains(new XsenseTopic.EntityConfig(DETECTOR));
    }

    @Test
    void should_read_the_self_test_of_a_detector() {
        assertThat(XsenseTopic.parse("xsense.sbs50.SBS50ABCDEF01_0000000B.self_test.state"))
            .contains(new XsenseTopic.SelfTest(DETECTOR));
    }

    @Test
    void should_read_the_availability_and_the_network_of_the_base() {
        assertThat(XsenseTopic.parse("xsense.sbs50.SBS50ABCDEF01.availability"))
            .contains(new XsenseTopic.BaseAvailability("SBS50ABCDEF01"));
        assertThat(XsenseTopic.parse("xsense.sbs50.SBS50ABCDEF01.network.state"))
            .contains(new XsenseTopic.BaseNetwork("SBS50ABCDEF01"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "homeassistant.status",
        "xsense.sbs50.SBS50ABCDEF01.device.0000000B.cmd",
        "xsense.sbs50.SBS50ABCDEF01.station.cmd",
        "xsense.sbs50.SBS50ABCDEF01.fire_drill.state",
        //the discovery of the base itself carries no detector id
        "homeassistant.binary_sensor.SBS50ABCDEF01.SBS50ABCDEF01_online.config",
        //the detector of the entity is another one than the one of the device
        "homeassistant.binary_sensor.SBS50ABCDEF01_0000000B.SBS50ABCDEF01_0000000A_online.state",
        "shellyplus1pm-0123456789ab.status.switch:0",
        "eaton.blinds.45",
        ""
    })
    void should_drop_every_other_routing_key(final String routingKey) {
        assertThat(XsenseTopic.parse(routingKey)).isEmpty();
    }

    @Test
    void should_drop_no_routing_key() {
        assertThat(XsenseTopic.parse(null)).isEmpty();
    }
}
