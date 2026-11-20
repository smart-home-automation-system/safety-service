package cloud.cholewa.safety.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The technical state of the detectors (HAS-463): every base station of the registry - whether it is on the broker
 * ({@code reachable}, its availability; left out while not known), when it was last heard (itself or any of its
 * detectors), its address and RSSI, whether it is silent -, how many detectors report a problem now (offline, low battery,
 * end of life, fault) and the open alerts of the technical monitor. The times are the wall clock of the house.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusReply(List<Base> bases, int detectorsInTrouble, List<OpenAlert> alerts) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Base(String stationSerial, Boolean reachable, LocalDateTime lastHeardAt, String ipAddress,
                       Integer wifiRssi, boolean silent) {
    }

    /** {@code told} false: the alert has not got through yet - the next pass tries again. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OpenAlert(String kind, String device, String name, LocalDateTime since, boolean told) {
    }
}
