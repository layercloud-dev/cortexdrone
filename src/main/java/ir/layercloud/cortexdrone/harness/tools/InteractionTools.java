package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.harness.tool.ToolArgs;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.tool.ToolAffinity;
import ir.layercloud.cortexdrone.harness.tool.ToolJson;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSchema;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.harness.tools.view.InteractionView;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.Arrays;
import java.util.List;

/**
 * One interaction per call: activate a block, use an item, attack, mount or
 * dismount. These are deliberately not merged into a generic "interact" tool.
 */
public final class InteractionTools implements ToolProvider {
    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("interact_block")
                    .description("Right-click exactly one block: door, lever, button, furnace, machine, "
                            + "chest, crafting table. Does NOT use the held item afterwards. Use 'side' "
                            + "(0=down,1=up,2=north,3=south,4=west,5=east) when the machine needs a face.")
                    .parameters(ToolSchema.object()
                            .requiredInt("x", "Block X.")
                            .requiredInt("y", "Block Y.")
                            .requiredInt("z", "Block Z.")
                            .optionalInt("side", "Clicked face: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east.", 0, 5, 1))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(30_000L)
                    .build(),
            ToolSpec.builder("use_item")
                    .description("Use exactly one held item, optionally aimed at a block. Select the item by "
                            + "name ('hoe', 'bucket', 'flint_and_steel') or by inventory slot. Does not "
                            + "activate the target block first.")
                    .parameters(ToolSchema.object()
                            .optionalString("item", "Item name/registry name/ore dictionary tag to hold.")
                            .optionalInt("slot", "Drone inventory slot to use instead of a name.", 0, 7, null)
                            .optionalInt("x", "Optional target block X.")
                            .optionalInt("y", "Optional target block Y.")
                            .optionalInt("z", "Optional target block Z.")
                            .optionalInt("side", "Target face when coordinates are given.", 0, 5, 1))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(30_000L)
                    .build(),
            ToolSpec.builder("attack_entity")
                    .description("Perform exactly one melee attack with the selected/held item. Get the "
                            + "entity_id from get_entities. The drone flies into reach first if needed. "
                            + "One attack per call; vanilla ignores hits inside the hurt-resistance window "
                            + "(the result reports nextAttackInMs), so pace swings with attack_plan or use "
                            + "the attack_to_death skill instead of spamming. Never attacks players.")
                    .parameters(ToolSchema.object()
                            .requiredInt("entity_id", "Entity id returned by get_entities.")
                            .optionalString("item", "Weapon name/registry/ore tag to swing instead of "
                                    + "the auto-selected best weapon.")
                            .optionalInt("slot", "Drone inventory slot to swing instead of a name.", 0, 7, null))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(30_000L)
                    .build(),
            ToolSpec.builder("pick_entity")
                    .description("Mount exactly one entity (including a player) on the drone. Get the id "
                            + "from get_entities. Compose transport by pick -> goto -> unpick.")
                    .parameters(ToolSchema.object()
                            .requiredInt("entity_id", "Entity id to mount."))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(30_000L)
                    .build(),
            ToolSpec.builder("unpick_entity")
                    .description("Dismount the current rider, or a specific entity when entity_id is given. "
                            + "Refuses to release a living rider unless the drone is over a safe landing "
                            + "column (solid floor, 2-block pocket, no water/lava/canopy); the result then has "
                            + "needsMovement with the nearest safe hover point. Fly there and call it again. "
                            + "Never drop a player mid-air: it is a lethal fall.")
                    .parameters(ToolSchema.object()
                            .optionalInt("entity_id", "Entity id to dismount; omit for the current rider.", null)
                            .optionalBoolean("force", "Release even when the landing check fails. Only when the "
                                    + "player explicitly asked to be dropped here.", false))
                    .affinity(ToolAffinity.MAIN)
                    .timeoutMs(20_000L)
                    .build()
    );

    private final InteractionView interaction;

    public InteractionTools(AIDroneManager manager) {
        this.interaction = new InteractionView(manager.getInteractionManager());
    }

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    @Override
    public void register(ToolRegistry registry) {
        registry.bind("interact_block", getClass().getSimpleName(),
                (context, arguments) -> interactBlock(context.controller(), context.player(), arguments));
        registry.bind("use_item", getClass().getSimpleName(),
                (context, arguments) -> useItem(context.controller(), context.player(), arguments));
        registry.bind("attack_entity", getClass().getSimpleName(),
                (context, arguments) -> this.interaction.handle(context.controller(), context.player(), "attack_entity", arguments));
        registry.bind("pick_entity", getClass().getSimpleName(),
                (context, arguments) -> this.interaction.handle(context.controller(), context.player(), "pick_entity", arguments));
        registry.bind("unpick_entity", getClass().getSimpleName(),
                (context, arguments) -> this.interaction.handle(context.controller(), context.player(), "unpick_entity", arguments));
    }

    private JsonObject interactBlock(DroneController controller, EntityPlayerMP player, JsonObject args) {
        int x = args.get("x").getAsInt();
        int y = args.get("y").getAsInt();
        int z = args.get("z").getAsInt();
        JsonObject unreachable = ReachSupport.unreachable(controller, x, y, z, "interact");
        if (unreachable != null) return unreachable;
        return this.interaction.handle(controller, player, "interact_block", args);
    }

    private JsonObject useItem(DroneController controller, EntityPlayerMP player, JsonObject args) {
        if (args.has("x") && args.has("y") && args.has("z")) {
            int x = args.get("x").getAsInt();
            int y = args.get("y").getAsInt();
            int z = args.get("z").getAsInt();
            JsonObject unreachable = ReachSupport.unreachable(controller, x, y, z, "use");
            if (unreachable != null) return unreachable;
        }
        return this.interaction.handle(controller, player, "use_item", args);
    }
}
