package cloud.cholewa.safety.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * A fire alarm episode as the API answers it (HAS-461): the detector by its registry id and its name in the registry now
 * (left out for a detector it no longer lists), the room of the alarm, when it started and cleared - {@code clearedAt}
 * left out while it lasts - and when the household was told of each, left out when it could not be told. The times are
 * the wall clock of the house.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlarmReply(
    long id,
    long detectorId,
    String name,
    String room,
    LocalDateTime startedAt,
    LocalDateTime clearedAt,
    LocalDateTime alarmToldAt,
    LocalDateTime overToldAt
) {
}
