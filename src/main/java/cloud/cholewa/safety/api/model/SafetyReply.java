package cloud.cholewa.safety.api.model;

import java.util.List;

/** Every fire detector of the registry, by id, and every base station heard, by serial. */
public record SafetyReply(List<DetectorReply> detectors, List<BaseReply> bases) {
}
