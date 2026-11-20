package cloud.cholewa.safety.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/** A base station of the detectors: whether it is on the broker, its address and Wi-Fi; a field not known left out. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BaseReply(
    String stationSerial,
    FlagReply online,
    String ipAddress,
    String wifiSsid,
    Integer wifiRssi,
    LocalDateTime lastHeardAt,
    boolean confirmed
) {
}
