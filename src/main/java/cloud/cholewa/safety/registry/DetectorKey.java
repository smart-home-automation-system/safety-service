package cloud.cholewa.safety.registry;

import java.util.Locale;

/**
 * A detector as its base names it in a topic: the serial of the base station and the id it gave the detector, compared
 * in capitals.
 */
public record DetectorKey(String stationSerial, String deviceId) {

    public DetectorKey {
        stationSerial = stationSerial.toUpperCase(Locale.ROOT);
        deviceId = deviceId.toUpperCase(Locale.ROOT);
    }
}
