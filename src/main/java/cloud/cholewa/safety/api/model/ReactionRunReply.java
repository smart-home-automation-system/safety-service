package cloud.cholewa.safety.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * A reaction sent at a fire alarm as the API answers it (HAS-468): the id of the alarm episode (left out when it was not
 * stored), the reaction by the name of its switch, the room or the air conditioner it went to (left out when the units
 * could not be read), when it was sent - the wall clock of the house - and its outcome: sent, none or failed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReactionRunReply(Long episode, String reaction, String target, LocalDateTime sentAt, String outcome) {
}
