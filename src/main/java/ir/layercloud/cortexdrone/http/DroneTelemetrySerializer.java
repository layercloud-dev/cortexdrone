package ir.layercloud.cortexdrone.http;

import ir.layercloud.cortexdrone.DroneController;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.TickProfiler;
import ir.layercloud.cortexdrone.config.AIConfig;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.harness.session.DroneSession;
import ir.layercloud.cortexdrone.manager.navigation.NavigationState;
import ir.layercloud.cortexdrone.manager.visuals.VisualsState;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.AxisAlignedBB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Serializes live server, player, and drone state/diagnostics into JSON snapshots
 * for `/state` and `/debug` HTTP endpoints.
 */
@RequiredArgsConstructor
public class DroneTelemetrySerializer {
    private final AIDroneManager manager;
    private final DroneLogger logger;

    public JsonObject buildDebugJson() {
        JsonObject root = new JsonObject();
        root.addProperty("status", "ok");
        root.addProperty("modId", "cortexdrone");
        root.addProperty("modName", "CortexDrone");
        root.addProperty("modVersion", CortexDroneMod.VERSION);
        root.addProperty("timestamp", System.currentTimeMillis());
        root.addProperty("debugLogFile", this.logger.getDebugLogFile() != null ? this.logger.getDebugLogFile().getAbsolutePath() : "");

        JsonArray dronesDiag = new JsonArray();
        for (DroneController ctrl : this.manager.getControllersById().values()) {
            Drone d = ctrl.drone;
            if (d == null || d.isDead) continue;
            JsonObject dj = new JsonObject();
            DroneSession session = this.manager.getSessionManager().session(ctrl);
            NavigationState nav = this.manager.getNavigationManager().state(ctrl);
            dj.addProperty("id", ctrl.id);
            dj.addProperty("entityId", d.getEntityId());
            dj.addProperty("name", ctrl.name);
            dj.addProperty("owner", ctrl.ownerName);
            dj.addProperty("lastThought", session.lastThought() != null ? session.lastThought() : "");
            dj.addProperty("navigationActive", this.manager.getNavigationManager().isActive(ctrl));
            dj.addProperty("x", Math.round(d.posX * 100.0) / 100.0);
            dj.addProperty("y", Math.round(d.posY * 100.0) / 100.0);
            dj.addProperty("z", Math.round(d.posZ * 100.0) / 100.0);
            dj.addProperty("vx", Math.round(d.motionX * 1000.0) / 1000.0);
            dj.addProperty("vy", Math.round(d.motionY * 1000.0) / 1000.0);
            dj.addProperty("vz", Math.round(d.motionZ * 1000.0) / 1000.0);
            dj.addProperty("targetX", d.targetX());
            dj.addProperty("targetY", d.targetY());
            dj.addProperty("targetZ", d.targetZ());
            dj.addProperty("stuckTicks", nav.stuckTicks);
            dj.addProperty("lastStuckReason", nav.lastStuckReason);
            dj.addProperty("lastPathNodesExpanded", nav.lastPathNodesExpanded);
            dj.addProperty("pathIndex", nav.pathIndex);

            JsonArray wpArr = new JsonArray();
            for (double[] wp : nav.pathWaypoints) {
                JsonArray pt = new JsonArray();
                pt.add(new JsonPrimitive(Math.round(wp[0] * 100.0) / 100.0));
                pt.add(new JsonPrimitive(Math.round(wp[1] * 100.0) / 100.0));
                pt.add(new JsonPrimitive(Math.round(wp[2] * 100.0) / 100.0));
                wpArr.add(pt);
            }
            dj.add("pathWaypoints", wpArr);

            JsonArray thArr = new JsonArray();
            for (String th : session.thoughtHistory()) {
                thArr.add(new JsonPrimitive(th));
            }
            dj.add("thoughtHistory", thArr);
            dj.addProperty("inventorySummary", InventoryService.formatInventorySummary(d.mainInventory()));
            dronesDiag.add(dj);
        }
        root.add("drones", dronesDiag);
        root.add("debugLogs", this.logger.toDebugJsonArray(150, 1600));
        return root;
    }

    public JsonObject buildStateJson() {
        JsonObject root = new JsonObject();
        root.addProperty("status", "ok");
        root.addProperty("modId", "cortexdrone");
        root.addProperty("modName", "CortexDrone");
        root.addProperty("modVersion", CortexDroneMod.VERSION);
        root.addProperty("timestamp", System.currentTimeMillis());

        MinecraftServer srv = MinecraftServer.getServer();
        JsonArray playersArr = new JsonArray();
        if (srv != null && srv.getConfigurationManager() != null) {
            List<?> pList = new ArrayList<Object>((java.util.Collection<?>) srv.getConfigurationManager().playerEntityList);
            for (Object o : pList) {
                if (o instanceof EntityPlayerMP) {
                    EntityPlayerMP p = (EntityPlayerMP) o;
                    JsonObject po = new JsonObject();
                    po.addProperty("name", p.getCommandSenderName());
                    po.addProperty("dimension", p.dimension);
                    po.addProperty("x", Math.round(p.posX * 100.0) / 100.0);
                    po.addProperty("y", Math.round(p.posY * 100.0) / 100.0);
                    po.addProperty("z", Math.round(p.posZ * 100.0) / 100.0);
                    po.addProperty("yaw", Math.round(p.rotationYaw * 10.0f) / 10.0f);
                    po.addProperty("pitch", Math.round(p.rotationPitch * 10.0f) / 10.0f);
                    po.addProperty("health", p.getHealth());
                    po.addProperty("authenticated", this.manager.getMod().isAuthenticated(p));
                    playersArr.add(po);
                }
            }
        }
        root.add("players", playersArr);

        JsonArray dronesArr = new JsonArray();
        for (DroneController ctrl : this.manager.getControllersById().values()) {
            Drone d = ctrl.drone;
            if (d == null || d.isDead) continue;
            JsonObject dj = new JsonObject();
            DroneSession session = this.manager.getSessionManager().session(ctrl);
            NavigationState nav = this.manager.getNavigationManager().state(ctrl);
            VisualsState visuals = this.manager.getVisualsManager().state(ctrl);
            dj.addProperty("id", ctrl.id);
            dj.addProperty("entityId", d.getEntityId());
            dj.addProperty("uuid", d.getUniqueID() != null ? d.getUniqueID().toString() : "");
            dj.addProperty("name", ctrl.name);
            dj.addProperty("owner", ctrl.ownerName);
            dj.addProperty("lastThought", session.lastThought() != null ? session.lastThought() : "");
            dj.addProperty("dimension", d.dimension);
            dj.addProperty("x", Math.round(d.posX * 100.0) / 100.0);
            dj.addProperty("y", Math.round(d.posY * 100.0) / 100.0);
            dj.addProperty("z", Math.round(d.posZ * 100.0) / 100.0);
            dj.addProperty("targetX", d.targetX());
            dj.addProperty("targetY", d.targetY());
            dj.addProperty("targetZ", d.targetZ());
            dj.addProperty("navigationActive", this.manager.getNavigationManager().isActive(ctrl));
            dj.addProperty("statusText", d.statusText());
            dj.addProperty("lightColor", String.format("0x%06X", d.lightColor() & 0xFFFFFF));
            dj.addProperty("isRunning", d.isRunning());
            dj.addProperty("energy", d.globalBuffer());
            dj.addProperty("maxEnergy", d.globalBufferSize());
            dj.addProperty("lightAura", visuals.lightAura);
            dj.addProperty("lightActive", visuals.lightAura);
            dj.addProperty("lightRadius", visuals.lightRadius);
            dj.addProperty("lightIntensity", visuals.lightIntensity);
            dj.addProperty("auraColor", String.format("0x%06X", visuals.lightColor & 0xFFFFFF));
            dj.addProperty("taskActive", session.isActive());
            dj.addProperty("taskSummary", session.getSummary());
            dj.addProperty("taskAgeMs", session.getAgeMs());
            dj.addProperty("navigationActive", this.manager.getNavigationManager().isActive(ctrl));
            dj.addProperty("pathWaypointCount", nav.pathWaypoints.size());

            JsonArray invArr = new JsonArray();
            IInventory inv = d.mainInventory();
            if (inv != null) {
                int size = inv.getSizeInventory();
                for (int i = 0; i < size; i++) {
                    ItemStack st = inv.getStackInSlot(i);
                    if (st != null && st.stackSize > 0) {
                        JsonObject slotObj = new JsonObject();
                        slotObj.addProperty("slot", i);
                        String reg = st.getItem() != null ? Item.itemRegistry.getNameForObject(st.getItem()) : "";
                        slotObj.addProperty("item", reg != null ? reg : st.getUnlocalizedName());
                        slotObj.addProperty("displayName", st.getDisplayName());
                        slotObj.addProperty("count", st.stackSize);
                        slotObj.addProperty("damage", st.getItemDamage());
                        invArr.add(slotObj);
                    }
                }
            }
            dj.add("inventory", invArr);

            JsonObject nearbyOres = new JsonObject();
            Map<String, int[]> scan = this.manager.getWorldScanner().scanNearbyResources(d.worldObj, d.posX, d.posY, d.posZ, 16);
            for (Map.Entry<String, int[]> e : scan.entrySet()) {
                int[] v = e.getValue();
                JsonObject ro = new JsonObject();
                ro.addProperty("count", v[0]);
                ro.addProperty("nearestX", v[1]);
                ro.addProperty("nearestY", v[2]);
                ro.addProperty("nearestZ", v[3]);
                nearbyOres.add(e.getKey(), ro);
            }
            dj.add("nearbyBlocks", nearbyOres);

            JsonArray nearbyEnts = new JsonArray();
            AxisAlignedBB eBox = AxisAlignedBB.getBoundingBox(
                    d.posX - 12.0, d.posY - 6.0, d.posZ - 12.0,
                    d.posX + 12.0, d.posY + 8.0, d.posZ + 12.0
            );
            List<?> ents = d.worldObj.getEntitiesWithinAABB(EntityLivingBase.class, eBox);
            if (ents != null) {
                for (Object eo : ents) {
                    if (eo instanceof EntityLivingBase) {
                        EntityLivingBase el = (EntityLivingBase) eo;
                        if (el.isDead) continue;
                        JsonObject eJson = new JsonObject();
                        eJson.addProperty("name", el.getCommandSenderName());
                        eJson.addProperty("hostile", ItemClassifier.isHostileOrMutantEntity(el));
                        eJson.addProperty("health", el.getHealth());
                        eJson.addProperty("x", Math.round(el.posX * 10.0) / 10.0);
                        eJson.addProperty("y", Math.round(el.posY * 10.0) / 10.0);
                        eJson.addProperty("z", Math.round(el.posZ * 10.0) / 10.0);
                        nearbyEnts.add(eJson);
                    }
                }
            }
            dj.add("nearbyEntities", nearbyEnts);
            dronesArr.add(dj);
        }
        root.add("drones", dronesArr);
        root.add("recentChat", this.logger.toChatJsonArray());
        root.add("recentDebug", this.logger.toDebugJsonArray(25));
        root.add("tps", buildTpsJson());

        AIConfig cfg = this.manager.getConfig();
        JsonObject cfgObj = new JsonObject();
        cfgObj.addProperty("apiUrl", cfg.getApiUrl());
        cfgObj.addProperty("model", cfg.getModel());
        cfgObj.addProperty("hasApiKey", cfg.getApiKey() != null && !cfg.getApiKey().isEmpty());
        root.add("aiConfig", cfgObj);
        return root;
    }

    private JsonObject buildTpsJson() {
        TickProfiler profiler = this.manager.getTickProfiler();
        JsonObject tps = new JsonObject();
        tps.addProperty("avgMs", round3(profiler.getAvgMs()));
        tps.addProperty("p95Ms", round3(profiler.getP95Ms()));
        tps.addProperty("maxMs", round3(profiler.getMaxMs()));
        tps.addProperty("lastMs", round3(profiler.getLastMs()));
        tps.addProperty("estimatedTps", round3(profiler.getEstimatedTps()));
        tps.addProperty("samples", profiler.getSampleCount());
        tps.addProperty("ticksMeasured", profiler.getTicksMeasured());
        tps.addProperty("overThresholdCount", profiler.getOverThresholdCount());
        tps.addProperty("warnThresholdMs", round3(profiler.getWarnThresholdMs()));
        tps.addProperty("warnCooldownMs", profiler.getWarnCooldownMs());
        tps.addProperty("lastWarnWallMs", profiler.getLastWarnWallMs());
        return tps;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
