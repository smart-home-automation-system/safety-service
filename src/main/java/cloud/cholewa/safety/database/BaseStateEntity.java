package cloud.cholewa.safety.database;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

//A row of base_state (V1). Written only by the upsert of StateStore, read back at the start
@Table("base_state")
public record BaseStateEntity(
    @Id String stationSerial,
    Boolean online,
    Instant onlineChangedAt,
    String ipAddress,
    String wifiSsid,
    Integer wifiRssi,
    Instant lastHeardAt,
    Instant updatedAt
) {
}
