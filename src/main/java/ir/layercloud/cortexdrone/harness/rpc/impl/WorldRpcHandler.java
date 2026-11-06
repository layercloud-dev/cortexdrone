package ir.layercloud.cortexdrone.harness.rpc.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.AIConfig;
import ir.layercloud.cortexdrone.harness.rpc.RpcHandler;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.service.world.ChunkAccess;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.StructureArchitect;
import ir.layercloud.cortexdrone.service.world.WorldScanner;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.*;

/**
 * Handles spatial world queries, 3D room/vein scans, build-site & blueprint planning,
 * entities, item drops, named locations, and registry/wiki documentation lookups.
 */
@RequiredArgsConstructor
public class WorldRpcHandler implements RpcHandler {
    private static final String[] METHODS = new String[]{
            "get_block", "get_blocks_in_box", "get_top_y", "scan_room", "scan_blocks",
            "scan_veins", "find_distant", "get_entities", "get_drops", "raycast_player",
            "find_base", "get_location", "list_locations", "lookup_doc",
            "find_build_site", "plan_structure"
    };

    private final AIDroneManager manager;
    private final WorldScanner worldScanner;
    private final StructureArchitect structureArchitect = new StructureArchitect();

    @Override
    public String[] getSupportedMethods() {
        return METHODS;
    }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        Drone d = ctrl.drone;
        World world = d.worldObj;

        if (!world.isRemote && !ChunkAccess.isBlockLoaded(world,
                MathHelper.floor_double(d.posX),
                MathHelper.floor_double(d.posZ))) {
            return chunkNotLoaded("drone chunk");
        }

        if (m.equals("get_block")) {
            int x = args.has("x") ? args.get("x").getAsInt() : MathHelper.floor_double(d.posX);
            int y = args.has("y") ? args.get("y").getAsInt() : MathHelper.floor_double(d.posY);
            int z = args.has("z") ? args.get("z").getAsInt() : MathHelper.floor_double(d.posZ);
            if (!ChunkAccess.isBlockLoaded(world, x, z)) {
                return chunkNotLoaded("get_block");
            }
            Block b = world.getBlock(x, y, z);
            int meta = world.getBlockMetadata(x, y, z);
            res.addProperty("x", x);
            res.addProperty("y", y);
            res.addProperty("z", z);
            res.addProperty("id", b != null ? Block.getIdFromBlock(b) : 0);
            res.addProperty("meta", meta);
            res.addProperty("name", b != null ? ItemClassifier.getBlockDisplayName(b, meta) : "Air");
            String reg = b != null ? Block.blockRegistry.getNameForObject(b) : "minecraft:air";
            res.addProperty("registryName", reg != null ? reg : "minecraft:air");
            res.addProperty("isAir", world.isAirBlock(x, y, z));
            res.addProperty("isLiquid", ItemClassifier.isLiquidBlock(b));
            res.addProperty("passable", ItemClassifier.isPassableVoxel(world, x, y, z));
            res.addProperty("hardness", b != null ? b.getBlockHardness(world, x, y, z) : 0.0f);
            res.addProperty("light", world.getBlockLightValue(x, y, z));
            return res;
        }

        if (m.equals("get_blocks_in_box")) {
            int x1 = args.get("x1").getAsInt(), y1 = args.get("y1").getAsInt(), z1 = args.get("z1").getAsInt();
            int x2 = args.get("x2").getAsInt(), y2 = args.get("y2").getAsInt(), z2 = args.get("z2").getAsInt();
            boolean includeAir = args.has("includeAir") && args.get("includeAir").getAsBoolean();
            int minX = Math.min(x1, x2), maxX = Math.min(minX + 32, Math.max(x1, x2));
            int minY = Math.max(1, Math.min(y1, y2)), maxY = Math.min(254, Math.max(y1, y2));
            int minZ = Math.min(z1, z2), maxZ = Math.min(minZ + 32, Math.max(z1, z2));
            for (int cx = (minX >> 4); cx <= (maxX >> 4); cx++) {
                for (int cz = (minZ >> 4); cz <= (maxZ >> 4); cz++) {
                    if (!ChunkAccess.isLoaded(world, cx, cz)) {
                        return chunkNotLoaded("get_blocks_in_box");
                    }
                }
            }
            Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
            JsonArray blocksArr = new JsonArray();
            int totalVoxels = 0;
            int nonAirCount = 0;
            for (int y = maxY; y >= minY; y--) {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        totalVoxels++;
                        boolean air = world.isAirBlock(x, y, z);
                        if (!air) nonAirCount++;
                        if (air && !includeAir) continue;
                        Block b = world.getBlock(x, y, z);
                        int meta = world.getBlockMetadata(x, y, z);
                        String bName = air ? "Air" : (b != null ? ItemClassifier.getBlockDisplayName(b, meta) : "Air");
                        counts.put(bName, (counts.containsKey(bName) ? counts.get(bName) : 0) + 1);
                        if (blocksArr.size() < 2500) {
                            JsonObject bo = new JsonObject();
                            bo.addProperty("x", x);
                            bo.addProperty("y", y);
                            bo.addProperty("z", z);
                            bo.addProperty("name", bName);
                            String reg = (!air && b != null) ? Block.blockRegistry.getNameForObject(b) : "minecraft:air";
                            bo.addProperty("registryName", reg != null ? reg : "minecraft:air");
                            bo.addProperty("id", b != null ? Block.getIdFromBlock(b) : 0);
                            bo.addProperty("meta", meta);
                            bo.addProperty("isAir", air);
                            bo.addProperty("isLiquid", ItemClassifier.isLiquidBlock(b));
                            blocksArr.add(bo);
                        }
                    }
                }
            }
            JsonObject countsJson = new JsonObject();
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                countsJson.addProperty(e.getKey(), e.getValue());
            }
            res.addProperty("totalVoxels", totalVoxels);
            res.addProperty("nonAirCount", nonAirCount);
            res.addProperty("count", nonAirCount);
            res.add("countsByType", countsJson);
            res.add("blocks", blocksArr);
            return res;
        }

        if (m.equals("get_top_y")) {
            int x = args.get("x").getAsInt();
            int z = args.get("z").getAsInt();
            if (!ChunkAccess.isBlockLoaded(world, x, z)) {
                return chunkNotLoaded("get_top_y");
            }
            int topY = world.getHeightValue(x, z);
            Block surf = world.getBlock(x, Math.max(1, topY - 1), z);
            BiomeGenBase bio = world.getBiomeGenForCoords(x, z);
            res.addProperty("x", x);
            res.addProperty("z", z);
            res.addProperty("topY", topY);
            res.addProperty("surfaceBlock", surf != null ? ItemClassifier.getBlockDisplayName(surf, world.getBlockMetadata(x, Math.max(1, topY - 1), z)) : "Air");
            res.addProperty("biome", bio != null && bio.biomeName != null ? bio.biomeName : "Unknown");
            return res;
        }

        if (m.equals("scan_room")) {
            int sx = args.has("x") ? args.get("x").getAsInt()
                    : MathHelper.floor_double(player != null ? player.posX : d.posX);
            int sy = args.has("y") ? args.get("y").getAsInt()
                    : MathHelper.floor_double((player != null ? player.posY : d.posY) + 0.5);
            int sz = args.has("z") ? args.get("z").getAsInt()
                    : MathHelper.floor_double(player != null ? player.posZ : d.posZ);
            int maxR = args.has("maxRadius") ? args.get("maxRadius").getAsInt() : 18;
            if (!ChunkAccess.isBlockLoaded(world, sx, sz)) {
                return chunkNotLoaded("scan_room");
            }
            return this.worldScanner.scanRoom3D(world, sx, sy, sz, maxR);
        }

        if (m.equals("scan_blocks")) {
            String filter = args.has("filter") ? args.get("filter").getAsString() : "ores";
            int radius = args.has("radius") ? Math.min(64, Math.max(1, args.get("radius").getAsInt())) : 24;
            final int cx = args.has("x") ? args.get("x").getAsInt() : MathHelper.floor_double(player != null ? player.posX : d.posX);
            final int cy = args.has("y") ? args.get("y").getAsInt() : MathHelper.floor_double(player != null ? player.posY : d.posY);
            final int cz = args.has("z") ? args.get("z").getAsInt() : MathHelper.floor_double(player != null ? player.posZ : d.posZ);
            int minY = args.has("minY") ? Math.max(1, args.get("minY").getAsInt())
                    : (!args.has("y") && ItemClassifier.isLikelyUndergroundQuery(filter) ? 1 : Math.max(1, cy - radius));
            int maxY = args.has("maxY") ? Math.min(254, args.get("maxY").getAsInt()) : Math.min(254, cy + radius);

            List<int[]> matched = new ArrayList<int[]>();
            for (int x = cx - radius; x <= cx + radius; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = cz - radius; z <= cz + radius; z++) {
                        if (!ChunkAccess.isBlockLoaded(world, x, z)) continue;
                        if (world.isAirBlock(x, y, z)) continue;
                        Block b = world.getBlock(x, y, z);
                        int meta = world.getBlockMetadata(x, y, z);
                        boolean match = filter.equalsIgnoreCase("liquid") ? ItemClassifier.isLiquidBlock(b) : ItemClassifier.matchesMineFilter(b, meta, filter);
                        if (match) {
                            int distSq = (x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz);
                            matched.add(new int[]{x, y, z, meta, distSq});
                        }
                    }
                }
            }
            Collections.sort(matched, new Comparator<int[]>() {
                @Override
                public int compare(int[] a, int[] b) {
                    return Integer.compare(a[4], b[4]);
                }
            });
            JsonArray arr = new JsonArray();
            int limit = Math.min(400, matched.size());
            for (int i = 0; i < limit; i++) {
                int[] mpt = matched.get(i);
                int x = mpt[0], y = mpt[1], z = mpt[2], meta = mpt[3];
                Block b = world.getBlock(x, y, z);
                JsonObject bo = new JsonObject();
                bo.addProperty("x", x);
                bo.addProperty("y", y);
                bo.addProperty("z", z);
                bo.addProperty("name", ItemClassifier.getBlockDisplayName(b, meta));
                String reg = b != null ? Block.blockRegistry.getNameForObject(b) : "";
                bo.addProperty("registryName", reg != null ? reg : "");
                bo.addProperty("id", b != null ? Block.getIdFromBlock(b) : 0);
                bo.addProperty("meta", meta);
                bo.addProperty("dist", Math.round(Math.sqrt(mpt[4]) * 10.0) / 10.0);
                arr.add(bo);
            }
            res.addProperty("count", matched.size());
            res.add("blocks", arr);
            return res;
        }

        if (m.equals("scan_veins")) {
            String filter = args.has("filter") ? args.get("filter").getAsString() : "ores";
            int radius = args.has("radius") ? Math.min(48, Math.max(4, args.get("radius").getAsInt())) : 28;
            int cx = args.has("x") ? args.get("x").getAsInt() : MathHelper.floor_double(player != null ? player.posX : d.posX);
            int cy = args.has("y") ? args.get("y").getAsInt() : MathHelper.floor_double(player != null ? player.posY : d.posY);
            int cz = args.has("z") ? args.get("z").getAsInt() : MathHelper.floor_double(player != null ? player.posZ : d.posZ);
            if (!ChunkAccess.isBlockLoaded(world, cx, cz)) {
                return chunkNotLoaded("scan_veins");
            }
            return this.worldScanner.scanConnectedVeins(world, cx, cy, cz, radius, filter);
        }

        if (m.equals("find_distant")) {
            String target = args.has("target") ? args.get("target").getAsString() : "jungle";
            int maxR = args.has("maxRadius") ? Math.min(1500, Math.max(64, args.get("maxRadius").getAsInt())) : 1200;
            double sx = player != null ? player.posX : d.posX;
            double sy = player != null ? player.posY : d.posY;
            double sz = player != null ? player.posZ : d.posZ;
            if (!ChunkAccess.isBlockLoaded(world, sx, sz)) {
                return chunkNotLoaded("find_distant");
            }
            List<int[]> found = this.worldScanner.findDistantResourceOrBiome(world, ctrl, sx, sy, sz, target, maxR);
            res.addProperty("found", !found.isEmpty());
            res.addProperty("count", found.size());
            JsonArray blocksArr = new JsonArray();
            for (int[] pt : found) {
                JsonArray pa = new JsonArray();
                pa.add(new JsonPrimitive(pt[0]));
                pa.add(new JsonPrimitive(pt[1]));
                pa.add(new JsonPrimitive(pt[2]));
                blocksArr.add(pa);
            }
            res.add("blocks", blocksArr);
            if (!found.isEmpty()) {
                int[] first = found.get(0);
                res.addProperty("x", first[0]);
                res.addProperty("y", first[1]);
                res.addProperty("z", first[2]);
                res.addProperty("distance", Math.round(Math.hypot(first[0] - sx, first[2] - sz)));
                BiomeGenBase bio = world.getBiomeGenForCoords(first[0], first[2]);
                res.addProperty("biome", bio != null && bio.biomeName != null ? bio.biomeName : "Unknown");
            }
            return res;
        }

        if (m.equals("get_entities")) {
            int radius = args.has("radius") ? Math.min(180, Math.max(4, args.get("radius").getAsInt())) : 64;
            String filter = args.has("filter") ? args.get("filter").getAsString() : "all";
            double cx = player != null ? player.posX : d.posX;
            double cy = player != null ? player.posY : d.posY;
            double cz = player != null ? player.posZ : d.posZ;
            AxisAlignedBB box = AxisAlignedBB.getBoundingBox(cx - radius, Math.max(1.0, cy - 32.0), cz - radius, cx + radius, Math.min(254.0, cy + 32.0), cz + radius);
            List<?> list = world.getEntitiesWithinAABB(EntityLivingBase.class, box);
            JsonArray arr = new JsonArray();
            if (list != null) {
                for (Object o : list) {
                    if (o instanceof EntityLivingBase) {
                        EntityLivingBase el = (EntityLivingBase) o;
                        if (ItemClassifier.matchesEntityFilter(el, filter)) {
                            JsonObject eo = new JsonObject();
                            eo.addProperty("id", el.getEntityId());
                            eo.addProperty("name", el.getCommandSenderName());
                            eo.addProperty("type", el.getClass().getSimpleName());
                            eo.addProperty("hostile", ItemClassifier.isHostileOrMutantEntity(el));
                            eo.addProperty("animal", el instanceof EntityAnimal);
                            eo.addProperty("health", el.getHealth());
                            eo.addProperty("x", Math.round(el.posX * 10.0) / 10.0);
                            eo.addProperty("y", Math.round(el.posY * 10.0) / 10.0);
                            eo.addProperty("z", Math.round(el.posZ * 10.0) / 10.0);
                            eo.addProperty("dist", Math.round(el.getDistance(cx, cy, cz) * 10.0) / 10.0);
                            arr.add(eo);
                        }
                    }
                }
            }
            res.addProperty("count", arr.size());
            res.add("entities", arr);
            return res;
        }

        if (m.equals("get_drops")) {
            int radius = args.has("radius") ? Math.min(128, Math.max(2, args.get("radius").getAsInt())) : 32;
            String filter = args.has("filter") ? args.get("filter").getAsString() : "all";
            boolean matchAll = filter.equalsIgnoreCase("all") || filter.equalsIgnoreCase("any") || filter.equals("*");
            AxisAlignedBB box = AxisAlignedBB.getBoundingBox(
                    d.posX - radius, Math.max(1.0, d.posY - 24.0), d.posZ - radius,
                    d.posX + radius, Math.min(254.0, d.posY + 24.0), d.posZ + radius
            );
            List<?> list = world.getEntitiesWithinAABB(EntityItem.class, box);
            JsonArray arr = new JsonArray();
            if (list != null) {
                for (Object o : list) {
                    if (o instanceof EntityItem) {
                        EntityItem ei = (EntityItem) o;
                        if (ei.isDead) continue;
                        ItemStack st = ei.getEntityItem();
                        if (st == null || st.stackSize <= 0 || st.getItem() == null) continue;
                        if (!matchAll && !ItemClassifier.matchesBlockOrItemName(st.getDisplayName(), ItemClassifier.getItemIdentifier(st), st.getItemDamage(), filter)) {
                            continue;
                        }
                        JsonObject io = ItemClassifier.itemStackToJson(-1, st);
                        io.addProperty("entityId", ei.getEntityId());
                        io.addProperty("x", Math.round(ei.posX * 10.0) / 10.0);
                        io.addProperty("y", Math.round(ei.posY * 10.0) / 10.0);
                        io.addProperty("z", Math.round(ei.posZ * 10.0) / 10.0);
                        io.addProperty("dist", Math.round(d.getDistanceToEntity(ei) * 10.0) / 10.0);
                        arr.add(io);
                    }
                }
            }
            res.addProperty("count", arr.size());
            res.add("drops", arr);
            return res;
        }

        if (m.equals("raycast_player")) {
            EntityPlayerMP p = player != null ? player : CortexDroneMod.findPlayer(ctrl.ownerName);
            double maxDist = args.has("maxDist") ? args.get("maxDist").getAsDouble() : (args.has("distance") ? args.get("distance").getAsDouble() : 80.0);
            JsonObject hit = WorldScanner.raycastPlayerLook(world, p, maxDist);
            res.addProperty("found", hit != null);
            if (hit != null) {
                res.add("hit", hit);
                for (Map.Entry<String, JsonElement> e : hit.entrySet()) {
                    res.add(e.getKey(), e.getValue());
                }
            }
            return res;
        }

        if (m.equals("find_base")) {
            double[] base = this.worldScanner.findBaseLocation(world, player);
            res.addProperty("x", Math.round(base[0] * 10.0) / 10.0);
            res.addProperty("y", Math.round(base[1] * 10.0) / 10.0);
            res.addProperty("z", Math.round(base[2] * 10.0) / 10.0);
            return res;
        }

        if (m.equals("get_location")) {
            EntityPlayerMP p = player != null ? player : CortexDroneMod.findPlayer(ctrl.ownerName);
            String ownerKey = p != null ? p.getCommandSenderName() : ctrl.ownerName;
            String name = args.has("name") ? args.get("name").getAsString()
                    : (args.has("query") ? args.get("query").getAsString() : "base");
            AIConfig.SavedLocation loc = this.manager.getConfig().getLocation(ownerKey, name);
            if (loc != null) {
                res.addProperty("found", true);
                res.addProperty("name", loc.getName());
                res.addProperty("owner", loc.getOwner());
                res.addProperty("x", loc.getX());
                res.addProperty("y", loc.getY());
                res.addProperty("z", loc.getZ());
                res.addProperty("tags", loc.getTags());
                return res;
            }
            if (name.equalsIgnoreCase("base") || name.equalsIgnoreCase("home")) {
                double[] base = this.worldScanner.findBaseLocation(world, p);
                res.addProperty("found", true);
                res.addProperty("name", "base");
                res.addProperty("x", Math.round(base[0] * 10.0) / 10.0);
                res.addProperty("y", Math.round(base[1] * 10.0) / 10.0);
                res.addProperty("z", Math.round(base[2] * 10.0) / 10.0);
                return res;
            }
            res.addProperty("found", false);
            return res;
        }

        if (m.equals("list_locations")) {
            EntityPlayerMP p = player != null ? player : CortexDroneMod.findPlayer(ctrl.ownerName);
            String ownerKey = p != null ? p.getCommandSenderName() : ctrl.ownerName;
            List<AIConfig.SavedLocation> list = this.manager.getConfig().listLocations(ownerKey);
            JsonArray arr = new JsonArray();
            for (AIConfig.SavedLocation loc : list) {
                JsonObject lo = new JsonObject();
                lo.addProperty("name", loc.getName());
                lo.addProperty("owner", loc.getOwner());
                lo.addProperty("x", loc.getX());
                lo.addProperty("y", loc.getY());
                lo.addProperty("z", loc.getZ());
                lo.addProperty("tags", loc.getTags() != null ? loc.getTags() : "");
                lo.addProperty("dist", Math.round(d.getDistance(loc.getX(), loc.getY(), loc.getZ()) * 10.0) / 10.0);
                arr.add(lo);
            }
            res.addProperty("count", arr.size());
            res.add("locations", arr);
            return res;
        }

        if (m.equals("lookup_doc")) {
            String query = args.has("query") ? args.get("query").getAsString()
                    : (args.has("item") ? args.get("item").getAsString() : "");
            String modId = args.has("modId") && !args.get("modId").isJsonNull() ? args.get("modId").getAsString() : null;
            boolean includeWeb = args.has("includeWeb") && args.get("includeWeb").getAsBoolean();
            return this.manager.getDocLookupService().lookup(query, modId, includeWeb);
        }

        if (m.equals("find_build_site")) {
            int width = args.has("width") ? args.get("width").getAsInt() : 5;
            int depth = args.has("depth") ? args.get("depth").getAsInt() : width;
            int maxR = args.has("maxRadius") ? args.get("maxRadius").getAsInt() : 24;
            return this.structureArchitect.findBestBuildSite(world, player, ctrl, width, depth, maxR);
        }

        if (m.equals("plan_structure")) {
            StructureArchitect.StructurePlan plan = this.structureArchitect.generateStructureBlueprint(world, player, ctrl, args);
            return plan.toJson();
        }

        res.addProperty("ok", false);
        res.addProperty("error", "Unhandled world RPC method: " + m);
        return res;
    }

    private static JsonObject chunkNotLoaded(String source) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", false);
        res.addProperty("error", "Chunk is not loaded: " + source);
        return res;
    }
}
