package ir.layercloud.cortexdrone.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.TickProfiler;
import ir.layercloud.cortexdrone.config.AIConfig;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.AxisAlignedBB;

/**
 * Serializes live server, player, and drone state/diagnostics into JSON snapshots
 * for `/state` and `/debug` HTTP endpoints.
 */
public class DroneTelemetrySerializer {
    private final AIDroneManager manager;
    private final DroneLogger logger;

    public DroneTelemetrySerializer(AIDroneManager manager, DroneLogger logger) {
        this.manager = manager;
        this.logger = logger;
    }

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
            if (d == null || d.field_70128_L) continue;
            JsonObject dj = new JsonObject();
            dj.addProperty("id", ctrl.id);
            dj.addProperty("entityId", d.func_145782_y());
            dj.addProperty("name", ctrl.name);
            dj.addProperty("owner", ctrl.ownerName);
            dj.addProperty("lastThought", ctrl.lastThought != null ? ctrl.lastThought : "");
            dj.addProperty("mode", ctrl.mode.name());
            dj.addProperty("x", Math.round(d.field_70165_t * 100.0) / 100.0);
            dj.addProperty("y", Math.round(d.field_70163_u * 100.0) / 100.0);
            dj.addProperty("z", Math.round(d.field_70161_v * 100.0) / 100.0);
            dj.addProperty("vx", Math.round(d.field_70159_w * 1000.0) / 1000.0);
            dj.addProperty("vy", Math.round(d.field_70181_x * 1000.0) / 1000.0);
            dj.addProperty("vz", Math.round(d.field_70179_y * 1000.0) / 1000.0);
            dj.addProperty("targetX", d.targetX());
            dj.addProperty("targetY", d.targetY());
            dj.addProperty("targetZ", d.targetZ());
            dj.addProperty("anchorX", Math.round(ctrl.anchorX * 100.0) / 100.0);
            dj.addProperty("anchorY", Math.round(ctrl.anchorY * 100.0) / 100.0);
            dj.addProperty("anchorZ", Math.round(ctrl.anchorZ * 100.0) / 100.0);
            dj.addProperty("playerLookHold", ctrl.playerLookHold);
            dj.addProperty("stuckTicks", ctrl.stuckTicks);
            dj.addProperty("lastStuckReason", ctrl.lastStuckReason);
            dj.addProperty("lastPathNodesExpanded", ctrl.lastPathNodesExpanded);
            dj.addProperty("pathIndex", ctrl.pathIndex);

            JsonArray wpArr = new JsonArray();
            for (double[] wp : ctrl.pathWaypoints) {
                JsonArray pt = new JsonArray();
                pt.add(new JsonPrimitive(Math.round(wp[0] * 100.0) / 100.0));
                pt.add(new JsonPrimitive(Math.round(wp[1] * 100.0) / 100.0));
                pt.add(new JsonPrimitive(Math.round(wp[2] * 100.0) / 100.0));
                wpArr.add(pt);
            }
            dj.add("pathWaypoints", wpArr);

            if (ctrl.currentMineTarget != null) {
                JsonObject mt = new JsonObject();
                mt.addProperty("x", ctrl.currentMineTarget[0]);
                mt.addProperty("y", ctrl.currentMineTarget[1]);
                mt.addProperty("z", ctrl.currentMineTarget[2]);
                Block b = d.field_70170_p.func_147439_a(ctrl.currentMineTarget[0], ctrl.currentMineTarget[1], ctrl.currentMineTarget[2]);
                mt.addProperty("block", b != null ? b.func_149732_F() : "air");
                dj.add("currentMineTarget", mt);
            }
            if (ctrl.currentMineStandoff != null) {
                JsonObject ms = new JsonObject();
                ms.addProperty("x", Math.round(ctrl.currentMineStandoff[0] * 100.0) / 100.0);
                ms.addProperty("y", Math.round(ctrl.currentMineStandoff[1] * 100.0) / 100.0);
                ms.addProperty("z", Math.round(ctrl.currentMineStandoff[2] * 100.0) / 100.0);
                dj.add("mineStandoff", ms);
            }
            dj.addProperty("miningProgressTicks", ctrl.mineProgressTicks);
            dj.addProperty("miningRequiredTicks", ctrl.miningRequiredTicks);
            dj.addProperty("mineQueueSize", ctrl.mineQueue.size());
            dj.addProperty("minedCount", ctrl.minedCount);
            JsonArray thArr = new JsonArray();
            for (String th : ctrl.thoughtHistory) {
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

        MinecraftServer srv = MinecraftServer.func_71276_C();
        JsonArray playersArr = new JsonArray();
        if (srv != null && srv.func_71203_ab() != null && srv.func_71203_ab().field_72404_b != null) {
            List<?> pList = new ArrayList<Object>(srv.func_71203_ab().field_72404_b);
            for (Object o : pList) {
                if (o instanceof EntityPlayerMP) {
                    EntityPlayerMP p = (EntityPlayerMP) o;
                    JsonObject po = new JsonObject();
                    po.addProperty("name", p.func_70005_c_());
                    po.addProperty("dimension", p.field_71093_bK);
                    po.addProperty("x", Math.round(p.field_70165_t * 100.0) / 100.0);
                    po.addProperty("y", Math.round(p.field_70163_u * 100.0) / 100.0);
                    po.addProperty("z", Math.round(p.field_70161_v * 100.0) / 100.0);
                    po.addProperty("yaw", Math.round(p.field_70177_z * 10.0f) / 10.0f);
                    po.addProperty("pitch", Math.round(p.field_70125_A * 10.0f) / 10.0f);
                    po.addProperty("health", p.func_110143_aJ());
                    po.addProperty("authenticated", this.manager.getMod().isAuthenticated(p));
                    playersArr.add(po);
                }
            }
        }
        root.add("players", playersArr);

        JsonArray dronesArr = new JsonArray();
        for (DroneController ctrl : this.manager.getControllersById().values()) {
            Drone d = ctrl.drone;
            if (d == null || d.field_70128_L) continue;
            JsonObject dj = new JsonObject();
            dj.addProperty("id", ctrl.id);
            dj.addProperty("entityId", d.func_145782_y());
            dj.addProperty("uuid", d.func_110124_au() != null ? d.func_110124_au().toString() : "");
            dj.addProperty("name", ctrl.name);
            dj.addProperty("owner", ctrl.ownerName);
            dj.addProperty("lastThought", ctrl.lastThought != null ? ctrl.lastThought : "");
            dj.addProperty("targetPlayer", ctrl.targetPlayerName);
            dj.addProperty("dimension", d.field_71093_bK);
            dj.addProperty("x", Math.round(d.field_70165_t * 100.0) / 100.0);
            dj.addProperty("y", Math.round(d.field_70163_u * 100.0) / 100.0);
            dj.addProperty("z", Math.round(d.field_70161_v * 100.0) / 100.0);
            dj.addProperty("targetX", d.targetX());
            dj.addProperty("targetY", d.targetY());
            dj.addProperty("targetZ", d.targetZ());
            dj.addProperty("mode", ctrl.mode.name());
            dj.addProperty("playerLookHold", ctrl.playerLookHold);
            dj.addProperty("statusText", d.statusText());
            dj.addProperty("lightColor", String.format("0x%06X", d.lightColor() & 0xFFFFFF));
            dj.addProperty("isRunning", d.isRunning());
            dj.addProperty("energy", d.globalBuffer());
            dj.addProperty("maxEnergy", d.globalBufferSize());
            dj.addProperty("autoTorch", ctrl.autoTorch);
            dj.addProperty("mineQueueSize", ctrl.mineQueue.size());
            dj.addProperty("pathWaypointCount", ctrl.pathWaypoints.size());

            JsonArray invArr = new JsonArray();
            IInventory inv = d.mainInventory();
            if (inv != null) {
                int size = inv.func_70302_i_();
                for (int i = 0; i < size; i++) {
                    ItemStack st = inv.func_70301_a(i);
                    if (st != null && st.field_77994_a > 0) {
                        JsonObject slotObj = new JsonObject();
                        slotObj.addProperty("slot", i);
                        String reg = st.func_77973_b() != null ? Item.field_150901_e.func_148750_c(st.func_77973_b()) : "";
                        slotObj.addProperty("item", reg != null ? reg : st.func_77977_a());
                        slotObj.addProperty("displayName", st.func_82833_r());
                        slotObj.addProperty("count", st.field_77994_a);
                        slotObj.addProperty("damage", st.func_77960_j());
                        invArr.add(slotObj);
                    }
                }
            }
            dj.add("inventory", invArr);

            JsonObject nearbyOres = new JsonObject();
            Map<String, int[]> scan = this.manager.getWorldScanner().scanNearbyResources(d.field_70170_p, d.field_70165_t, d.field_70163_u, d.field_70161_v, 16);
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
            AxisAlignedBB eBox = AxisAlignedBB.func_72330_a(
                d.field_70165_t - 12.0, d.field_70163_u - 6.0, d.field_70161_v - 12.0,
                d.field_70165_t + 12.0, d.field_70163_u + 8.0, d.field_70161_v + 12.0
            );
            List<?> ents = d.field_70170_p.func_72872_a(EntityLivingBase.class, eBox);
            if (ents != null) {
                for (Object eo : ents) {
                    if (eo instanceof EntityLivingBase) {
                        EntityLivingBase el = (EntityLivingBase) eo;
                        if (el.field_70128_L) continue;
                        JsonObject eJson = new JsonObject();
                        eJson.addProperty("name", el.func_70005_c_());
                        eJson.addProperty("hostile", ItemClassifier.isHostileOrMutantEntity(el));
                        eJson.addProperty("health", el.func_110143_aJ());
                        eJson.addProperty("x", Math.round(el.field_70165_t * 10.0) / 10.0);
                        eJson.addProperty("y", Math.round(el.field_70163_u * 10.0) / 10.0);
                        eJson.addProperty("z", Math.round(el.field_70161_v * 10.0) / 10.0);
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
        cfgObj.addProperty("apiUrl", cfg.apiUrl);
        cfgObj.addProperty("model", cfg.model);
        cfgObj.addProperty("hasApiKey", cfg.apiKey != null && !cfg.apiKey.isEmpty());
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
