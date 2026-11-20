package cloud.cholewa.safety.detector;

import java.time.Instant;
import java.util.Objects;

/**
 * What the service knows of one base station, by its serial: whether it is on the broker (its availability - its own
 * word or the last will the broker publishes for it), its address and Wi-Fi from its network state, when it was last
 * heard, when the snapshot was taken and whether a message confirmed it since the start. A field never heard is null.
 * The RSSI moves with every network message, so it is kept but is no change worth a write on its own.
 */
public record BaseState(
    String stationSerial,
    Flag online,
    String ipAddress,
    String wifiSsid,
    Integer wifiRssi,
    Instant lastHeardAt,
    Instant updatedAt,
    boolean confirmed
) {

    public static BaseState unknown(final String stationSerial, final Instant at) {
        return new BaseState(stationSerial, null, null, null, null, null, at, true);
    }

    public BaseState withOnline(final boolean value, final Instant at) {
        return new BaseState(stationSerial, Flag.next(online, value, at), ipAddress, wifiSsid, wifiRssi, at, at, true);
    }

    /** The state after its network message; a field it does not carry keeps what was known. */
    public BaseState withNetwork(final String ipAddress, final String wifiSsid, final Integer wifiRssi, final Instant at) {
        return new BaseState(stationSerial, online,
            ipAddress == null ? this.ipAddress : ipAddress,
            wifiSsid == null ? this.wifiSsid : wifiSsid,
            wifiRssi == null ? this.wifiRssi : wifiRssi,
            at, at, true);
    }

    public boolean storedDiffers(final BaseState other) {
        return other == null
            || !Objects.equals(online, other.online)
            || !Objects.equals(ipAddress, other.ipAddress)
            || !Objects.equals(wifiSsid, other.wifiSsid);
    }
}
