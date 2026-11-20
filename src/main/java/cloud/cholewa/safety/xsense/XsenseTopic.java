package cloud.cholewa.safety.xsense;

import cloud.cholewa.safety.registry.DetectorKey;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A routing key of what an X-Sense base station publishes (firmware 1.8.7, seen 2026-11-16 and 2026-11-20 - the topics
 * in {@code deployment-tools/scripts/xsense/devices.md}); the MQTT topic with every {@code /} turned into {@code .}.
 * Every other routing key of the device queue is none of these and is dropped without a word.
 * <ul>
 *   <li>{@code homeassistant.binary_sensor.<station>_<id>.<station>_<id>_<entity>.state} - a flag of a detector,
 *       {@code {"status": "<word>"}};</li>
 *   <li>{@code homeassistant.binary_sensor.<station>_<id>.<station>_<id>_<entity>.config} - its discovery, the model
 *       and the firmware in its {@code device} block;</li>
 *   <li>{@code xsense.sbs50.<station>_<id>.self_test.state} - its self test, {@code {"problem": <boolean>, ...}};</li>
 *   <li>{@code xsense.sbs50.<station>.availability} - the base, {@code online} / {@code offline} as plain text;</li>
 *   <li>{@code xsense.sbs50.<station>.network.state} - the base, {@code {"ip_address", "wifi_ssid", "wifi_rssi"}}.</li>
 * </ul>
 */
public sealed interface XsenseTopic {

    //the serial of an SBS50 (SBS50 and eight hex digits) and the id it gives a detector (eight hex digits)
    String STATION = "(SBS50[0-9A-Fa-f]{8})";
    String DEVICE = "([0-9A-Fa-f]{8})";

    Pattern DETECTOR_ENTITY = Pattern.compile(
        "^homeassistant\\.binary_sensor\\." + STATION + "_" + DEVICE + "\\.\\1_\\2_([a-z0-9_]+)\\.(state|config)$");
    Pattern SELF_TEST = Pattern.compile("^xsense\\.sbs50\\." + STATION + "_" + DEVICE + "\\.self_test\\.state$");
    Pattern BASE = Pattern.compile("^xsense\\.sbs50\\." + STATION + "\\.(availability|network\\.state)$");

    /** A flag of a detector: the entity names which ({@link DetectorFlag#ofEntity}). */
    record EntityState(DetectorKey detector, String entity) implements XsenseTopic {
    }

    /** The discovery of one entity of a detector - each carries the same device block. */
    record EntityConfig(DetectorKey detector) implements XsenseTopic {
    }

    record SelfTest(DetectorKey detector) implements XsenseTopic {
    }

    record BaseAvailability(String stationSerial) implements XsenseTopic {
    }

    record BaseNetwork(String stationSerial) implements XsenseTopic {
    }

    static Optional<XsenseTopic> parse(final String routingKey) {
        if (routingKey == null) {
            return Optional.empty();
        }
        Matcher matcher = DETECTOR_ENTITY.matcher(routingKey);
        if (matcher.matches()) {
            final DetectorKey key = new DetectorKey(matcher.group(1), matcher.group(2));
            return Optional.of("state".equals(matcher.group(4))
                ? new EntityState(key, matcher.group(3))
                : new EntityConfig(key));
        }
        matcher = SELF_TEST.matcher(routingKey);
        if (matcher.matches()) {
            return Optional.of(new SelfTest(new DetectorKey(matcher.group(1), matcher.group(2))));
        }
        matcher = BASE.matcher(routingKey);
        if (matcher.matches()) {
            final String station = matcher.group(1).toUpperCase(Locale.ROOT);
            return Optional.of("availability".equals(matcher.group(2))
                ? new BaseAvailability(station)
                : new BaseNetwork(station));
        }
        return Optional.empty();
    }
}
