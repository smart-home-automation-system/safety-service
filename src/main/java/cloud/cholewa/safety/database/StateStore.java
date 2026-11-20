package cloud.cholewa.safety.database;

import cloud.cholewa.safety.detector.BaseState;
import cloud.cholewa.safety.detector.DetectorFlag;
import cloud.cholewa.safety.detector.DetectorState;
import cloud.cholewa.safety.detector.Flag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * Writes the state of a detector or a base whole, read it all back at the start. One statement each, PostgreSQL's own:
 * a row per thing, replaced unless the row holds a newer snapshot (two threads of the listener may hand their snapshots
 * to the writer out of order), and a value the snapshot does not know (null) never replaces a stored one - a start that
 * could not read the rows back must not wipe what the base will not send again (the model and firmware of its
 * discovery). A write is bounded and tried twice more with a backoff, never for a refusal of the schema; whatever is
 * left is a WARN - the state in memory stays what the API answers ({@link StateWriter} runs the writes off the listener).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StateStore {

    static final Duration WRITE_TIMEOUT = Duration.ofSeconds(30);
    static final int WRITE_RETRIES = 2;
    static final Duration WRITE_BACKOFF = Duration.ofSeconds(1);

    private static final String UPSERT_DETECTOR = """
        INSERT INTO detector_state (detector_id, alarm, alarm_changed_at, online, online_changed_at, battery_low,
            battery_low_changed_at, end_of_life, end_of_life_changed_at, fault, fault_changed_at, muted, muted_changed_at,
            self_test_problem, self_test_problem_changed_at, model, firmware, last_heard_at, updated_at)
        VALUES (:detectorId, :alarm, :alarmChangedAt, :online, :onlineChangedAt, :batteryLow, :batteryLowChangedAt,
            :endOfLife, :endOfLifeChangedAt, :fault, :faultChangedAt, :muted, :mutedChangedAt, :selfTestProblem,
            :selfTestProblemChangedAt, :model, :firmware, :lastHeardAt, :updatedAt)
        ON CONFLICT (detector_id) DO UPDATE
        SET alarm = COALESCE(EXCLUDED.alarm, detector_state.alarm),
            alarm_changed_at = COALESCE(EXCLUDED.alarm_changed_at, detector_state.alarm_changed_at),
            online = COALESCE(EXCLUDED.online, detector_state.online),
            online_changed_at = COALESCE(EXCLUDED.online_changed_at, detector_state.online_changed_at),
            battery_low = COALESCE(EXCLUDED.battery_low, detector_state.battery_low),
            battery_low_changed_at = COALESCE(EXCLUDED.battery_low_changed_at, detector_state.battery_low_changed_at),
            end_of_life = COALESCE(EXCLUDED.end_of_life, detector_state.end_of_life),
            end_of_life_changed_at = COALESCE(EXCLUDED.end_of_life_changed_at, detector_state.end_of_life_changed_at),
            fault = COALESCE(EXCLUDED.fault, detector_state.fault),
            fault_changed_at = COALESCE(EXCLUDED.fault_changed_at, detector_state.fault_changed_at),
            muted = COALESCE(EXCLUDED.muted, detector_state.muted),
            muted_changed_at = COALESCE(EXCLUDED.muted_changed_at, detector_state.muted_changed_at),
            self_test_problem = COALESCE(EXCLUDED.self_test_problem, detector_state.self_test_problem),
            self_test_problem_changed_at = COALESCE(EXCLUDED.self_test_problem_changed_at, detector_state.self_test_problem_changed_at),
            model = COALESCE(EXCLUDED.model, detector_state.model),
            firmware = COALESCE(EXCLUDED.firmware, detector_state.firmware),
            last_heard_at = COALESCE(EXCLUDED.last_heard_at, detector_state.last_heard_at),
            updated_at = EXCLUDED.updated_at
        WHERE EXCLUDED.updated_at >= detector_state.updated_at
        """;

    private static final String UPSERT_BASE = """
        INSERT INTO base_state (station_serial, online, online_changed_at, ip_address, wifi_ssid, wifi_rssi, last_heard_at,
            updated_at)
        VALUES (:stationSerial, :online, :onlineChangedAt, :ipAddress, :wifiSsid, :wifiRssi, :lastHeardAt, :updatedAt)
        ON CONFLICT (station_serial) DO UPDATE
        SET online = COALESCE(EXCLUDED.online, base_state.online),
            online_changed_at = COALESCE(EXCLUDED.online_changed_at, base_state.online_changed_at),
            ip_address = COALESCE(EXCLUDED.ip_address, base_state.ip_address),
            wifi_ssid = COALESCE(EXCLUDED.wifi_ssid, base_state.wifi_ssid),
            wifi_rssi = COALESCE(EXCLUDED.wifi_rssi, base_state.wifi_rssi),
            last_heard_at = COALESCE(EXCLUDED.last_heard_at, base_state.last_heard_at),
            updated_at = EXCLUDED.updated_at
        WHERE EXCLUDED.updated_at >= base_state.updated_at
        """;

    private final DatabaseClient databaseClient;
    private final DetectorStateRepository detectorStates;
    private final BaseStateRepository baseStates;

    public Mono<Void> write(final DetectorState state) {
        GenericExecuteSpec spec = databaseClient.sql(UPSERT_DETECTOR).bind("detectorId", state.detectorId());
        spec = bindFlag(spec, "alarm", state.flag(DetectorFlag.ALARM));
        spec = bindFlag(spec, "online", state.flag(DetectorFlag.ONLINE));
        spec = bindFlag(spec, "batteryLow", state.flag(DetectorFlag.BATTERY_LOW));
        spec = bindFlag(spec, "endOfLife", state.flag(DetectorFlag.END_OF_LIFE));
        spec = bindFlag(spec, "fault", state.flag(DetectorFlag.FAULT));
        spec = bindFlag(spec, "muted", state.flag(DetectorFlag.MUTED));
        spec = bindFlag(spec, "selfTestProblem", state.flag(DetectorFlag.SELF_TEST_PROBLEM));
        spec = bind(spec, "model", state.model(), String.class);
        spec = bind(spec, "firmware", state.firmware(), String.class);
        spec = bind(spec, "lastHeardAt", state.lastHeardAt(), Instant.class);
        spec = spec.bind("updatedAt", state.updatedAt());
        return run(spec, "detector " + state.detectorId());
    }

    public Mono<Void> write(final BaseState state) {
        GenericExecuteSpec spec = databaseClient.sql(UPSERT_BASE).bind("stationSerial", state.stationSerial());
        spec = bindFlag(spec, "online", state.online());
        spec = bind(spec, "ipAddress", state.ipAddress(), String.class);
        spec = bind(spec, "wifiSsid", state.wifiSsid(), String.class);
        spec = bind(spec, "wifiRssi", state.wifiRssi(), Integer.class);
        spec = bind(spec, "lastHeardAt", state.lastHeardAt(), Instant.class);
        spec = spec.bind("updatedAt", state.updatedAt());
        return run(spec, "base station " + state.stationSerial());
    }

    /** Every stored detector, not confirmed. */
    public Flux<DetectorState> detectors() {
        return detectorStates.findAll().map(StateStore::detectorOf);
    }

    /** Every stored base, not confirmed. */
    public Flux<BaseState> bases() {
        return baseStates.findAll().map(row -> new BaseState(row.stationSerial(),
            flagOf(row.online(), row.onlineChangedAt()), row.ipAddress(), row.wifiSsid(), row.wifiRssi(),
            row.lastHeardAt(), row.updatedAt(), false));
    }

    private static Mono<Void> run(final GenericExecuteSpec spec, final String what) {
        return spec.fetch().rowsUpdated()
            .timeout(WRITE_TIMEOUT)
            .retryWhen(Retry.backoff(WRITE_RETRIES, WRITE_BACKOFF)
                .filter(failure -> !(failure instanceof NonTransientDataAccessException)))
            .doOnError(failure -> log.warn("The state of the {} was not stored: {}", what, failure.toString()))
            .onErrorComplete()
            .then();
    }

    private static DetectorState detectorOf(final DetectorStateEntity row) {
        final Map<DetectorFlag, Flag> flags = new EnumMap<>(DetectorFlag.class);
        put(flags, DetectorFlag.ALARM, row.alarm(), row.alarmChangedAt());
        put(flags, DetectorFlag.ONLINE, row.online(), row.onlineChangedAt());
        put(flags, DetectorFlag.BATTERY_LOW, row.batteryLow(), row.batteryLowChangedAt());
        put(flags, DetectorFlag.END_OF_LIFE, row.endOfLife(), row.endOfLifeChangedAt());
        put(flags, DetectorFlag.FAULT, row.fault(), row.faultChangedAt());
        put(flags, DetectorFlag.MUTED, row.muted(), row.mutedChangedAt());
        put(flags, DetectorFlag.SELF_TEST_PROBLEM, row.selfTestProblem(), row.selfTestProblemChangedAt());
        return new DetectorState(row.detectorId(), flags, row.model(), row.firmware(), row.lastHeardAt(),
            row.updatedAt(), false);
    }

    private static void put(final Map<DetectorFlag, Flag> flags, final DetectorFlag flag, final Boolean value,
                            final Instant changedAt) {
        final Flag known = flagOf(value, changedAt);
        if (known != null) {
            flags.put(flag, known);
        }
    }

    private static Flag flagOf(final Boolean value, final Instant changedAt) {
        return value == null || changedAt == null ? null : new Flag(value, changedAt);
    }

    private static GenericExecuteSpec bindFlag(final GenericExecuteSpec spec, final String name, final Flag flag) {
        return bind(bind(spec, name, flag == null ? null : flag.value(), Boolean.class),
            name + "ChangedAt", flag == null ? null : flag.changedAt(), Instant.class);
    }

    private static GenericExecuteSpec bind(final GenericExecuteSpec spec, final String name, final Object value,
                                           final Class<?> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }
}
