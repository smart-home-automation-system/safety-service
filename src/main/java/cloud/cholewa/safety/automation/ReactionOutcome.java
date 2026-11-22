package cloud.cholewa.safety.automation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * What a call of a reaction came to (HAS-468) - the outcome column of {@code safety_reaction_run}, pinned by its CHECK:
 * the service took it, there was nothing to do (a room without lights or blinds answers 404), or it failed.
 */
@Getter
@RequiredArgsConstructor
public enum ReactionOutcome {
    SENT("sent"),
    NONE("none"),
    FAILED("failed");

    private final String value;
}
