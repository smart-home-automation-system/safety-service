package cloud.cholewa.safety.registry;

import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.RoomName;

/**
 * A fire detector of the device registry: its registry id, name, room and kind, and the pair that finds it in the
 * topics of its base - the serial of the base station and the id the base gave it, both in capitals (the rule of
 * database-service).
 */
public record Detector(long id, String name, RoomName room, DeviceKind kind, String stationSerial, String deviceId) {

    public DetectorKey key() {
        return new DetectorKey(stationSerial, deviceId);
    }
}
