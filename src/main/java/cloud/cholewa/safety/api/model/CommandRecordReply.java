package cloud.cholewa.safety.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * One of the last commands of a detector (HAS-464, {@code GET /{id}/commands}): its request id, the command, who sent
 * it, when, and when the base answered it - left out while it has not (or never did).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandRecordReply(String requestId, String command, String source, LocalDateTime sentAt,
                                 LocalDateTime confirmedAt) {
}
