package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.tool.*;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.machine.FurnaceScanner;
import ir.layercloud.cortexdrone.service.machine.MachineTileInspector;
import ir.layercloud.cortexdrone.service.navigation.DroneGeometry;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityFurnace;

import java.util.Arrays;
import java.util.List;

/**
 * Furnace and machine read tools.
 *
 * <p>Split out of {@code InventoryQueryTools}. Read-only: a furnace is never
 * advanced by the mod, the world ticks it.</p>
 */
@SuppressWarnings("unused")
public final class MachineQueryTools {
    private final InventoryService inventoryService;

    public MachineQueryTools(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("scan_furnaces")
                    .description("Scan nearby furnaces and report position, burn/cook progress and every "
                            + "slot's contents. Read-only: it never advances a furnace. To smelt, move "
                            + "items into it from within reach and let the world tick it.")
                    .parameters(ToolSchema.object()
                            .optionalInt("radius", "Search radius, " + InventoryLimits.MIN_RADIUS + ".." + InventoryLimits.MAX_FURNACE_RADIUS + ".",
                                    InventoryLimits.MIN_RADIUS, InventoryLimits.MAX_FURNACE_RADIUS,
                                    ir.layercloud.cortexdrone.harness.tool.ScanLimits.DEFAULT_FURNACE_RADIUS))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.FAST_MS).build(),
            ToolSpec.builder("scan_machines")
                    .description("Scan nearby machine tiles and describe each one: kind, power/fluid status, "
                            + "inputs/outputs and progress. filter/machine narrows by machine kind.")
                    .parameters(ToolSchema.object()
                            .optionalString("machine", "Machine kind/alias, or a name substring.", "")
                            .optionalInt("radius", "Search radius, " + InventoryLimits.MIN_RADIUS + ".." + InventoryLimits.MAX_RADIUS + ".", InventoryLimits.MIN_RADIUS,
                                    InventoryLimits.MAX_RADIUS, InventoryLimits.DEFAULT_RADIUS))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.READ_MS).build()
    );

    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String tool, JsonObject args) {
        switch (tool) {
            case "scan_furnaces":
                return furnaces(ctrl, args);
            case "scan_machines":
                return machines(ctrl, args);
            default:
                return ToolJson.error("unknown_tool", "Unhandled machine tool: " + tool, null, false);
        }
    }

    private static JsonObject furnaces(DroneController ctrl, JsonObject args) {
        li.cil.oc.common.entity.Drone drone = ctrl.drone;
        int radius = InventoryToolSupport.furnaceRadius(args,
                ir.layercloud.cortexdrone.harness.tool.ScanLimits.DEFAULT_FURNACE_RADIUS);
        List<TileEntity> tiles = FurnaceScanner.findAllFurnaceTiles(
                drone.worldObj, drone.posX, drone.posY, drone.posZ, radius);
        JsonArray furnaces = new JsonArray();
        for (TileEntity tile : tiles) {
            if (!(tile instanceof IInventory)) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("x", tile.xCoord);
            entry.addProperty("y", tile.yCoord);
            entry.addProperty("z", tile.zCoord);
            entry.addProperty("dist", Math.round(drone.getDistance(
                    tile.xCoord + DroneGeometry.CELL_CENTER, tile.yCoord + DroneGeometry.CELL_CENTER, tile.zCoord + DroneGeometry.CELL_CENTER) * 10.0) / 10.0);
            if (tile instanceof TileEntityFurnace) {
                TileEntityFurnace furnace = (TileEntityFurnace) tile;
                entry.addProperty("burnTime", furnace.furnaceBurnTime);
                entry.addProperty("cookTime", furnace.furnaceCookTime);
            }
            entry.add("items", InventoryToolSupport.itemsOf((IInventory) tile));
            furnaces.add(entry);
        }
        JsonObject result = ToolJson.ok();
        result.addProperty("count", furnaces.size());
        result.add("furnaces", furnaces);
        return result;
    }

    private static JsonObject machines(DroneController ctrl, JsonObject args) {
        li.cil.oc.common.entity.Drone drone = ctrl.drone;
        int radius = InventoryToolSupport.radius(args, InventoryLimits.DEFAULT_RADIUS);
        String machine = InventoryToolSupport.machine(args);
        String wanted = MachineTileInspector.normalizeMachineAlias(machine);
        List<TileEntity> tiles = MachineTileInspector.findMachineTiles(
                drone.worldObj, drone.posX, drone.posY, drone.posZ, radius, wanted);
        JsonArray machines = new JsonArray();
        for (TileEntity tile : tiles) {
            machines.add(MachineTileInspector.describeMachineTile(drone.worldObj, tile));
        }
        JsonObject result = ToolJson.ok();
        result.addProperty("count", machines.size());
        result.add("machines", machines);
        return result;
    }
}
