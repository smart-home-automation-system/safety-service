package cloud.cholewa.safety.api.model;

import java.time.LocalDateTime;

/** A yes / no and since when, in the time of the house. */
public record FlagReply(boolean value, LocalDateTime since) {
}
