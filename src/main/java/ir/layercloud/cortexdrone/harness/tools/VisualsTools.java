package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.harness.tools.view.VisualsView;

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
                            + "[r,g,b] 0..255. Animate by looping this call inside execute.")
                    .parameters(ToolSchema.object()
                            .optionalString("color", "Packed int, hex string, named colour, or 'r,g,b'.", "#00ffff"))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(5_000L)
                    .build(),
            ToolSpec.builder("set_light")
                    .description("Enable/disable or reconfigure the illumination aura around the drone. "
                            + "The aura restores the original block light when cleared.")
                    .parameters(ToolSchema.object()
                            .optionalBoolean("enabled", "Turn the aura on or off.")
                            .optionalBoolean("toggle", "Toggle the current aura state instead of setting it.", false)
                            .optionalInt("radius", "Aura radius in blocks, 1..32.", 1, 32, 6)
                            .optionalInt("level", "Light level 0..15.", 0, 15, 14)
                            .optionalString("color", "Packed int or hex colour for the aura light."))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(10_000L)
                    .build(),
            ToolSpec.builder("spawn_particles")
                    .description("Spawn one particle burst. type is a Minecraft particle name such as "
                            + "'heart', 'flame', 'smoke', 'reddust', 'happyVillager', 'portal', 'crit'. "
                            + "With x/y/z the burst appears there, otherwise at the drone.")
                    .parameters(ToolSchema.object()
                            .optionalString("type", "Particle name.", "heart")
                            .optionalInt("count", "Particles in this burst, 1..80.", 1, 80, 12)
                            .optionalNumber("spread", "Random spread in blocks.", 0.35)
                            .optionalNumber("speed", "Particle motion speed.", 0.05)
                            .optionalNumber("x", "Optional burst X.")
                            .optionalNumber("y", "Optional burst Y.")
                            .optionalNumber("z", "Optional burst Z."))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(5_000L)
                    .build(),
            ToolSpec.builder("play_sound")
                    .description("Play one sound effect at the drone. sound is a Minecraft sound name such as "
                            + "'note.pling', 'random.levelup', 'mob.zombie.say', or a mod sound id.")
                    .parameters(ToolSchema.object()
                            .optionalString("sound", "Sound event name.", "note.pling")
                            .optionalNumber("volume", "Volume 0..4.", 0.0, 4.0, 1.0)
                            .optionalNumber("pitch", "Pitch 0.5..2.", 0.5, 2.0, 1.0))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(5_000L)
                    .build(),
            ToolSpec.builder("play_note")
                    .description("Play one note block note by semitone (0..24) at the drone.")
                    .parameters(ToolSchema.object()
                            .optionalInt("semitone", "Note index 0..24.", 0, 24, 12))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(5_000L)
                    .build(),
            ToolSpec.builder("say")
                    .description("Send one chat message as the drone. Use it for progress/blocker updates; "
                            + "never spam more than one message per few steps. The harness also speaks your "
                            + "final answer.")
                    .parameters(ToolSchema.object()
                            .requiredString("message", "Chat text (keep under 240 characters)."))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(5_000L)
                    .build(),
            ToolSpec.builder("set_status_text")
                    .description("Set the short status text rendered above the drone (max ~10 characters per "
                            + "line). Useful for 'Mining', 'Delivering', 'Blocked'.")
                    .parameters(ToolSchema.object()
                            .requiredString("text", "Status text."))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(5_000L)
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
