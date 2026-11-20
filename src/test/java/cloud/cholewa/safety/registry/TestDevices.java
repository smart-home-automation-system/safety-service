package cloud.cholewa.safety.registry;

import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.home.model.XsenseBinding;

//a smoke detector of the registry on an invented base station - never a real serial in this public repository
public final class TestDevices {

    public static final String STATION = "SBS50ABCDEF01";

    private TestDevices() {
    }

    public static SmartDevice detector(final long id, final String deviceId, final RoomName room) {
        return SmartDevice.builder()
            .id(id)
            .name("Smoke detector")
            .room(room)
            .kind(DeviceKind.SMOKE_DETECTOR)
            .enabled(true)
            .vendor(SmartVendor.XSENSE)
            .xsense(XsenseBinding.builder().stationSerial(STATION).deviceId(deviceId).build())
            .build();
    }
}
