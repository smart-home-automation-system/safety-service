package cloud.cholewa.safety.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * A fire detector as the API answers it: what the registry says of it, then what its base said - a field not known is
 * left out, never a default. {@code confirmed} false: the state was read back at the start and no message of the base
 * has confirmed it since.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DetectorReply(
    long id,
    String name,
    String room,
    String kind,
    String stationSerial,
    String deviceId,
    FlagReply alarm,
    FlagReply online,
    FlagReply batteryLow,
    FlagReply endOfLife,
    FlagReply fault,
    FlagReply muted,
    FlagReply selfTestProblem,
    String model,
    String firmware,
    LocalDateTime lastHeardAt,
    Boolean confirmed
) {
}
