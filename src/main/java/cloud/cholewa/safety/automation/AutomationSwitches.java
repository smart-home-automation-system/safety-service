package cloud.cholewa.safety.automation;

/**
 * The switches of the reactions to a fire alarm (HAS-468), each off until the administrator switches it on - the row
 * of {@code safety_automation}.
 */
public record AutomationSwitches(boolean lightsOn, boolean blindsUp, boolean airconOff) {

    public boolean on(final Reaction reaction) {
        return switch (reaction) {
            case LIGHTS_ON -> lightsOn;
            case BLINDS_UP -> blindsUp;
            case AIRCON_OFF -> airconOff;
        };
    }

    public boolean anyOn() {
        return lightsOn || blindsUp || airconOff;
    }
}
