package cloud.cholewa.safety.automation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A reaction to a fire alarm (HAS-468) - its word is the name of its switch in the API and the reaction column of
 * {@code safety_reaction_run}, pinned by its CHECK.
 */
@Getter
@RequiredArgsConstructor
public enum Reaction {
    //the lights of the room of the detector and of both halls switched on (light-service, source=safety)
    LIGHTS_ON("lightsOn"),
    //the blinds of the room of the detector opened (blinds-service, source=safety)
    BLINDS_UP("blindsUp"),
    //every air conditioner that is on switched off (aircon-service, source=safety)
    AIRCON_OFF("airconOff");

    private final String value;
}
