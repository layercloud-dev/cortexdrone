package ir.layercloud.cortexdrone.action.visuals;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable request data for one visuals action.
 */
@Value
@Builder
public class VisualsRequest {
    String effect;
    Integer red;
    Integer green;
    Integer blue;
    Float hue;
    Float saturation;
    Float brightness;
    String colorText;
    Integer colorValue;

    String type;
    int count;
    double spread;
    double speed;
    boolean coordinates;
    double x;
    double y;
    double z;

    String sound;
    float volume;
    float pitch;
    int semitone;

    String message;
    String text;
}
