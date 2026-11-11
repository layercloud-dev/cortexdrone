package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.harness.tool.*;
import ir.layercloud.cortexdrone.harness.tools.view.VisualsView;
import ir.layercloud.cortexdrone.service.visuals.VisualLimits;

import java.util.Arrays;
import java.util.List;

/**
 * Feedback and lighting. LED animation is a skill/loop, not a Java effect
 * engine; this provider only exposes static primitives.
 */
public final class VisualsTools implements ToolProvider {
    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("set_led")
                    .description("Set the drone's LED to one static colour. Accepts a packed integer, a hex "
                            + "string ('#ff8800' or '0xff8800'), a named colour (red, green, cyan...), or "
                            + "[r,g,b] 0.." + VisualLimits.MAX_COLOR + ". Animate by looping this call inside execute.")
                    .parameters(ToolSchema.object()
                            .optionalString("color", "Packed int, hex string, named colour, or 'r,g,b'.", "#00ffff"))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.READ_MS)
                    .capabilities(ToolCapability.PRESENTATION)
                    .build(),
            ToolSpec.builder("set_light")
                    .description("Enable/disable or reconfigure the illumination aura around the drone. "
                            + "The aura restores the original block light when cleared.")
                    .parameters(ToolSchema.object()
                            .optionalBoolean("enabled", "Turn the aura on or off.")
                            .optionalBoolean("toggle", "Toggle the current aura state instead of setting it.", false)
                            .optionalInt("radius", "Aura radius in blocks, "
                                            + VisualLimits.MIN_RADIUS + ".." + VisualLimits.MAX_RADIUS + ".",
                                    VisualLimits.MIN_RADIUS, VisualLimits.MAX_RADIUS,
                                    VisualLimits.DEFAULT_RADIUS)
                            .optionalInt("level", "Light level " + VisualLimits.MIN_LEVEL + ".." + VisualLimits.MAX_LEVEL + ".",
                                    VisualLimits.MIN_LEVEL, VisualLimits.MAX_LEVEL,
                                    VisualLimits.DEFAULT_LEVEL)
                            .optionalString("color", "Packed int or hex colour for the aura light."))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.MAIN_MS)
                    .capabilities(ToolCapability.PRESENTATION)
                    .build(),
            ToolSpec.builder("spawn_particles")
                    .description("Spawn one particle burst. type is a Minecraft particle name such as "
                            + "'heart', 'flame', 'smoke', 'reddust', 'happyVillager', 'portal', 'crit'. "
                            + "With x/y/z the burst appears there, otherwise at the drone.")
                    .parameters(ToolSchema.object()
                            .optionalString("type", "Particle name.", "heart")
                            .optionalInt("count", "Particles in this burst, " + VisualLimits.MIN_PARTICLES + ".."
                                            + VisualLimits.MAX_PARTICLES + ".", VisualLimits.MIN_PARTICLES,
                                    VisualLimits.MAX_PARTICLES, VisualLimits.DEFAULT_PARTICLES)
                            .optionalNumber("spread", "Random spread in blocks.", 0.35)
                            .optionalNumber("speed", "Particle motion speed.", 0.05)
                            .optionalNumber("x", "Optional burst X.")
                            .optionalNumber("y", "Optional burst Y.")
                            .optionalNumber("z", "Optional burst Z."))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.READ_MS)
                    .capabilities(ToolCapability.PRESENTATION)
                    .build(),
            ToolSpec.builder("play_sound")
                    .description("Play one sound effect at the drone. sound is a Minecraft sound name such as "
                            + "'note.pling', 'random.levelup', 'mob.zombie.say', or a mod sound id.")
                    .parameters(ToolSchema.object()
                            .optionalString("sound", "Sound event name.", "note.pling")
                            .optionalNumber("volume", "Volume " + VisualLimits.MIN_VOLUME + ".." + VisualLimits.MAX_VOLUME + ".",
                                    VisualLimits.MIN_VOLUME, VisualLimits.MAX_VOLUME,
                                    VisualLimits.DEFAULT_VOLUME)
                            .optionalNumber("pitch", "Pitch " + VisualLimits.MIN_PITCH + ".." + VisualLimits.MAX_PITCH + ".",
                                    VisualLimits.MIN_PITCH, VisualLimits.MAX_PITCH,
                                    VisualLimits.DEFAULT_PITCH))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.READ_MS)
                    .capabilities(ToolCapability.PRESENTATION)
                    .build(),
            ToolSpec.builder("play_note")
                    .description("Play one note block note by semitone (" + VisualLimits.MIN_SEMITONE + ".."
                            + VisualLimits.MAX_SEMITONE + ") at the drone.")
                    .parameters(ToolSchema.object()
                            .optionalInt("semitone", "Note index " + VisualLimits.MIN_SEMITONE + ".."
                                            + VisualLimits.MAX_SEMITONE + ".", VisualLimits.MIN_SEMITONE,
                                    VisualLimits.MAX_SEMITONE, VisualLimits.DEFAULT_SEMITONE))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.READ_MS)
                    .capabilities(ToolCapability.PRESENTATION)
                    .build(),
            ToolSpec.builder("say")
                    .description("Send one chat message as the drone. Use it for progress/blocker updates; "
                            + "never spam more than one message per few steps. The harness also speaks your "
                            + "final answer.")
                    .parameters(ToolSchema.object()
                            .requiredString("message", "Chat text (keep under 240 characters)."))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.READ_MS)
                    .capabilities(ToolCapability.PRESENTATION)
                    .build(),
            ToolSpec.builder("set_status_text")
                    .description("Set the short status text rendered above the drone (max ~10 characters per "
                            + "line). Useful for 'Mining', 'Delivering', 'Blocked'.")
                    .parameters(ToolSchema.object()
                            .requiredString("text", "Status text."))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(ToolTimeouts.READ_MS)
                    .capabilities(ToolCapability.PRESENTATION)
                    .build()
    );

    private final VisualsView visuals;

    public VisualsTools(AIDroneManager manager) {
        this.visuals = new VisualsView(manager.getVisualsManager());
    }

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    @Override
    public void register(ToolRegistry registry) {
        for (ToolSpec spec : SPECS) {
            final String name = spec.getName();
            registry.bind(name, getClass().getSimpleName(),
                    (context, arguments) -> this.visuals.handle(context.controller(), context.player(), name, arguments));
        }
    }
}
