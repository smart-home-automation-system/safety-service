package cloud.cholewa.safety.detector;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SafetyStatesTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private final SafetyStates states = new SafetyStates();

    @Test
    void should_answer_a_state_to_store_only_when_a_stored_value_changes() {
        assertThat(states.changeDetector(150L, id -> DetectorState.unknown(id, NOW),
            state -> state.withFlag(DetectorFlag.ALARM, false, NOW))).isPresent();
        //the same value again: confirmed and heard in memory, nothing to store
        assertThat(states.changeDetector(150L, id -> DetectorState.unknown(id, NOW),
            state -> state.withFlag(DetectorFlag.ALARM, false, NOW.plusSeconds(60)))).isEmpty();
        assertThat(states.changeDetector(150L, id -> DetectorState.unknown(id, NOW),
            state -> state.withFlag(DetectorFlag.ALARM, true, NOW.plusSeconds(120)))).isPresent();

        assertThat(states.detector(150L)).hasValueSatisfying(state -> {
            assertThat(state.flag(DetectorFlag.ALARM)).isEqualTo(new Flag(true, NOW.plusSeconds(120)));
            assertThat(state.lastHeardAt()).isEqualTo(NOW.plusSeconds(120));
        });
    }

    //a flag keeps the moment it took its value through messages that repeat it
    @Test
    void should_keep_since_when_a_flag_holds() {
        states.changeDetector(150L, id -> DetectorState.unknown(id, NOW),
            state -> state.withFlag(DetectorFlag.ONLINE, true, NOW));
        states.changeDetector(150L, id -> DetectorState.unknown(id, NOW),
            state -> state.withFlag(DetectorFlag.ONLINE, true, NOW.plusSeconds(3600)));

        assertThat(states.detector(150L)).hasValueSatisfying(state ->
            assertThat(state.flag(DetectorFlag.ONLINE).changedAt()).isEqualTo(NOW));
    }

    //a state read back is not confirmed, and never replaces one a message set first
    @Test
    void should_restore_a_stored_state_only_where_no_message_set_one() {
        states.changeDetector(150L, id -> DetectorState.unknown(id, NOW),
            state -> state.withFlag(DetectorFlag.ALARM, false, NOW));

        states.restore(new DetectorState(150L, Map.of(DetectorFlag.ALARM, new Flag(true, NOW.minusSeconds(60))),
            null, null, NOW.minusSeconds(60), NOW.minusSeconds(60), false));
        states.restore(new DetectorState(151L, Map.of(DetectorFlag.ALARM, new Flag(false, NOW.minusSeconds(60))),
            "XS01-M", "v1.8.0", NOW.minusSeconds(60), NOW.minusSeconds(60), false));

        assertThat(states.detector(150L)).hasValueSatisfying(state -> {
            assertThat(state.flag(DetectorFlag.ALARM).value()).isFalse();
            assertThat(state.confirmed()).isTrue();
        });
        assertThat(states.detector(151L)).hasValueSatisfying(state -> assertThat(state.confirmed()).isFalse());
    }

    //a message confirms a state read back, without a write when it says what the database holds
    @Test
    void should_confirm_a_restored_state_with_the_next_message() {
        states.restore(new DetectorState(150L, Map.of(DetectorFlag.ALARM, new Flag(false, NOW.minusSeconds(60))),
            null, null, NOW.minusSeconds(60), NOW.minusSeconds(60), false));

        assertThat(states.changeDetector(150L, id -> DetectorState.unknown(id, NOW),
            state -> state.withFlag(DetectorFlag.ALARM, false, NOW))).isEmpty();

        assertThat(states.detector(150L)).hasValueSatisfying(state -> {
            assertThat(state.confirmed()).isTrue();
            assertThat(state.flag(DetectorFlag.ALARM).changedAt()).isEqualTo(NOW.minusSeconds(60));
        });
    }

    @Test
    void should_keep_what_a_discovery_does_not_carry() {
        states.changeDetector(150L, id -> DetectorState.unknown(id, NOW),
            state -> state.withDevice("XS01-M", "v1.8.0", NOW));

        assertThat(states.changeDetector(150L, id -> DetectorState.unknown(id, NOW),
            state -> state.withDevice(null, null, NOW.plusSeconds(1)))).isEmpty();
        assertThat(states.detector(150L)).hasValueSatisfying(state -> assertThat(state.model()).isEqualTo("XS01-M"));
    }

    @Test
    void should_store_a_base_on_a_change_of_its_availability_or_address_not_of_its_rssi() {
        assertThat(states.changeBase("SBS50ABCDEF01", serial -> BaseState.unknown(serial, NOW),
            base -> base.withNetwork("192.0.2.82", "iot", -35, NOW))).isPresent();
        assertThat(states.changeBase("SBS50ABCDEF01", serial -> BaseState.unknown(serial, NOW),
            base -> base.withNetwork("192.0.2.82", "iot", -30, NOW.plusSeconds(60)))).isEmpty();
        assertThat(states.changeBase("SBS50ABCDEF01", serial -> BaseState.unknown(serial, NOW),
            base -> base.withOnline(false, NOW.plusSeconds(120)))).isPresent();

        assertThat(states.base("SBS50ABCDEF01")).hasValueSatisfying(base -> {
            assertThat(base.wifiRssi()).isEqualTo(-30);
            assertThat(base.online()).isEqualTo(new Flag(false, NOW.plusSeconds(120)));
        });
    }
}
