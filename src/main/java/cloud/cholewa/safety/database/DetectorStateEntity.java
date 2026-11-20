package cloud.cholewa.safety.database;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

//A row of detector_state (V1). Written only by the upsert of StateStore, read back at the start
@Table("detector_state")
public record DetectorStateEntity(
    @Id Long detectorId,
    Boolean alarm,
    Instant alarmChangedAt,
    Boolean online,
    Instant onlineChangedAt,
    Boolean batteryLow,
    Instant batteryLowChangedAt,
    Boolean endOfLife,
    Instant endOfLifeChangedAt,
    Boolean fault,
    Instant faultChangedAt,
    Boolean muted,
    Instant mutedChangedAt,
    Boolean selfTestProblem,
    Instant selfTestProblemChangedAt,
    String model,
    String firmware,
    Instant lastHeardAt,
    Instant updatedAt
) {
}
