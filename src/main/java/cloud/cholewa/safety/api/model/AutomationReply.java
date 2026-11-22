package cloud.cholewa.safety.api.model;

/**
 * The switches of the reactions to a fire alarm as the API answers them (HAS-468) - and the whole body of their
 * {@code PUT}: the lights of the room of the detector and of both halls on, the blinds of its room up, every air
 * conditioner off.
 */
public record AutomationReply(boolean lightsOn, boolean blindsUp, boolean airconOff) {
}
