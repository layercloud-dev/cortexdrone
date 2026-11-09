package ir.layercloud.cortexdrone.harness.tools;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.harness.tool.ToolContext;
import ir.layercloud.cortexdrone.harness.tool.ToolProvider;
import ir.layercloud.cortexdrone.harness.tool.ToolRegistry;
import ir.layercloud.cortexdrone.harness.tool.ToolSpec;
import ir.layercloud.cortexdrone.service.inventory.ContainerAccess;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only inventory tools: player, chest, furnace, crafting and machine
 * inspection. Mutating inventory tools are actions in
 * {@code action/inventory} and are handled by {@code InventoryManager}.
 *
 * <p>This class is the catalog entry point only. Handlers live in
 * {@link PlayerContainerQueryTools}, {@link MachineQueryTools} and
 * {@link RecipeQueryTools}; a new read-only tool belongs in one of those.</p>
 */
public final class InventoryQueryTools implements ToolProvider {
    private final PlayerContainerQueryTools playerContainers;
    private final MachineQueryTools machines;
    private final RecipeQueryTools recipes;

    public InventoryQueryTools(InventoryService inventoryService) {
        this.playerContainers = new PlayerContainerQueryTools(inventoryService);
        this.machines = new MachineQueryTools(inventoryService);
        this.recipes = new RecipeQueryTools(inventoryService);
    }

    public static List<ToolSpec> specs() {
        List<ToolSpec> specs = new ArrayList<>();
        specs.addAll(PlayerContainerQueryTools.SPECS);
        specs.addAll(MachineQueryTools.SPECS);
        specs.addAll(RecipeQueryTools.SPECS);
        return specs;
    }

    @Override
    public void register(ToolRegistry registry) {
        bind(registry, PlayerContainerQueryTools.SPECS, this.playerContainers::handle);
        bind(registry, MachineQueryTools.SPECS, this.machines::handle);
        bind(registry, RecipeQueryTools.SPECS, this.recipes::handle);
    }

    private interface Handler {
        JsonObject handle(DroneController ctrl, EntityPlayerMP player, String tool, JsonObject args);
    }

    private void bind(ToolRegistry registry, List<ToolSpec> specs, Handler handler) {
        for (ToolSpec spec : specs) {
            final String name = spec.getName();
            registry.bind(name, getClass().getSimpleName(), (context, arguments) -> {
                JsonObject result = handler.handle(context.controller(), context.player(), name, arguments);
                rememberScanned(context, result);
                return result;
            });
        }
    }

    /**
     * A scan that finds a machine inside the drone's reach is a visit: remember
     * its position and the approach trail so a later task can fly straight back.
     * Nothing is written for machines the drone could not actually open.
     */
    private static void rememberScanned(ToolContext context, JsonObject result) {
        if (result == null || !result.has("ok") || !result.get("ok").getAsBoolean()) return;
        if (context == null || context.controller() == null
                || context.controller().drone == null || context.controller().drone.worldObj == null) return;
        com.google.gson.JsonArray entries = result.has("machines")
                ? result.getAsJsonArray("machines")
                : (result.has("furnaces") ? result.getAsJsonArray("furnaces") : null);
        if (entries == null || entries.size() == 0) return;
        World world = context.controller().drone.worldObj;
        List<TileEntity> reachable = ContainerAccess.reachable(world,
                context.controller().drone, tilesFrom(world, entries));
        if (reachable.isEmpty()) return;
        PlaceMemory.rememberTile(context, context.controller(), world, reachable.get(0));
    }

    private static List<TileEntity> tilesFrom(World world, com.google.gson.JsonArray entries) {
        List<TileEntity> tiles = new ArrayList<>();
        for (JsonElement element : entries) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            if (!object.has("x") || !object.has("y") || !object.has("z")) continue;
            TileEntity tile = world.getTileEntity(object.get("x").getAsInt(),
                    object.get("y").getAsInt(), object.get("z").getAsInt());
            if (tile != null) tiles.add(tile);
        }
        return tiles;
    }
}
