package ir.layercloud.cortexdrone.harness.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.DroneController;
import ir.layercloud.cortexdrone.harness.tool.*;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.WorldBounds;
import li.cil.oc.common.entity.Drone;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.List;

/**
 * Entity and dropped-item listings around the drone.
 *
 * <p>Bounded by a radius and a vertical window so a crowded area cannot turn a
 * read-only query into a full-entity scan of the world.</p>
 */
public final class WorldEntityTools implements ToolProvider {
    /**
     * Largest entity search radius.
     */
    public static final int MAX_ENTITY_RADIUS = ScanLimits.MAX_ENTITY_RADIUS;
    /**
     * Largest dropped-item search radius.
     */
    public static final int MAX_DROP_RADIUS = ScanLimits.MAX_DROP_RADIUS;
    /**
     * Vertical half-window for entity and drop searches.
     */
    public static final int VERTICAL_WINDOW = 32;
    /**
     * Vertical half-window for dropped items (items rarely fly far vertically).
     */
    public static final int DROP_VERTICAL_WINDOW = 24;

    private static final List<ToolSpec> SPECS = Arrays.asList(
            ToolSpec.builder("get_entities")
                    .description("List living entities with id, name, type, hostile/animal flags, health, "
                            + "position and distance around the DRONE (anchor=player for the player). "
                            + "filter: all, hostile, animal, player, or a name substring.")
                    .parameters(ToolSchema.object()
                            .optionalString("filter", "all, hostile, animal, player, or a name substring.", "all")
                            .optionalString("anchor", "drone (default) or player.", "drone")
                            .optionalInt("radius", "Search radius, " + ScanLimits.MIN_ENTITY_RADIUS + ".." + ScanLimits.MAX_ENTITY_RADIUS + ".", ScanLimits.MIN_ENTITY_RADIUS,
                                    MAX_ENTITY_RADIUS, ScanLimits.DEFAULT_ENTITY_RADIUS))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.FAST_MS).build(),
            ToolSpec.builder("get_drops")
                    .description("List nearby dropped item entities with item name/count and entity id. "
                            + "Use filter to find a specific drop before suck_items.")
                    .parameters(ToolSchema.object()
                            .optionalInt("radius", "Search radius, " + ScanLimits.MIN_DROP_RADIUS + ".." + ScanLimits.MAX_DROP_RADIUS + ".", ScanLimits.MIN_DROP_RADIUS,
                                    MAX_DROP_RADIUS, ScanLimits.DEFAULT_DROP_RADIUS)
                            .optionalString("filter", "Item name/registry/ore tag or 'all'.", "all"))
                    .affinity(ToolAffinity.MAIN).timeoutMs(ToolTimeouts.FAST_MS).build()
    );

    public static List<ToolSpec> specs() {
        return SPECS;
    }

    @Override
    public void register(ToolRegistry registry) {
        for (ToolSpec spec : SPECS) {
            final String name = spec.getName();
            registry.bind(name, getClass().getSimpleName(),
                    (context, arguments) -> handle(context.controller(), context.player(), name, arguments));
        }
    }

    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String tool, JsonObject args) {
        Drone drone = ctrl != null ? ctrl.drone : null;
        if (drone == null || drone.worldObj == null) {
            return ToolJson.error("no_drone", "This tool needs a linked drone.", "Spawn or link a drone first.", false);
        }
        switch (tool) {
            case "get_entities":
                return entities(drone, player, args);
            case "get_drops":
                return drops(drone, args);
            default:
                return ToolJson.error("unknown_tool", "Unhandled entity tool: " + tool, null, false);
        }
    }

    private static JsonObject entities(Drone drone, EntityPlayerMP player, JsonObject args) {
        World world = drone.worldObj;
        int radius = args.has("radius")
                ? Math.max(ScanLimits.MIN_ENTITY_RADIUS,
                Math.min(MAX_ENTITY_RADIUS, args.get("radius").getAsInt()))
                : ScanLimits.DEFAULT_ENTITY_RADIUS;
        String filter = args.has("filter") ? args.get("filter").getAsString() : "all";
        int[] center = WorldToolSupport.scanCenter(drone, player, args);
        double cx = center[0], cy = center[1], cz = center[2];
        AxisAlignedBB box = AxisAlignedBB.getBoundingBox(cx - radius,
                Math.max((double) WorldBounds.MIN_Y, cy - VERTICAL_WINDOW), cz - radius,
                cx + radius, Math.min((double) WorldBounds.MAX_Y, cy + VERTICAL_WINDOW), cz + radius);
        List<?> living = world.getEntitiesWithinAABB(EntityLivingBase.class, box);
        JsonArray entities = new JsonArray();
        if (living != null) {
            for (Object candidate : living) {
                if (!(candidate instanceof EntityLivingBase)) continue;
                EntityLivingBase entity = (EntityLivingBase) candidate;
                if (!ItemClassifier.matchesEntityFilter(entity, filter)) continue;
                JsonObject entry = new JsonObject();
                entry.addProperty("id", entity.getEntityId());
                entry.addProperty("name", entity.getCommandSenderName());
                entry.addProperty("type", entity.getClass().getSimpleName());
                entry.addProperty("hostile", ItemClassifier.isHostileOrMutantEntity(entity));
                entry.addProperty("animal", entity instanceof EntityAnimal);
                entry.addProperty("player", entity instanceof EntityPlayer);
                entry.addProperty("health", entity.getHealth());
                entry.addProperty("x", Math.round(entity.posX * 10.0) / 10.0);
                entry.addProperty("y", Math.round(entity.posY * 10.0) / 10.0);
                entry.addProperty("z", Math.round(entity.posZ * 10.0) / 10.0);
                entry.addProperty("dist", Math.round(entity.getDistance(cx, cy, cz) * 10.0) / 10.0);
                entities.add(entry);
            }
        }
        JsonObject out = ToolJson.ok();
        out.addProperty("count", entities.size());
        out.add("entities", entities);
        return out;
    }

    private static JsonObject drops(Drone drone, JsonObject args) {
        World world = drone.worldObj;
        int radius = args.has("radius")
                ? Math.max(ScanLimits.MIN_DROP_RADIUS,
                Math.min(MAX_DROP_RADIUS, args.get("radius").getAsInt()))
                : ScanLimits.DEFAULT_DROP_RADIUS;
        String filter = args.has("filter") ? args.get("filter").getAsString() : "all";
        boolean matchAll = "all".equalsIgnoreCase(filter) || "any".equalsIgnoreCase(filter)
                || "*".equals(filter);
        AxisAlignedBB box = AxisAlignedBB.getBoundingBox(
                drone.posX - radius,
                Math.max((double) WorldBounds.MIN_Y, drone.posY - DROP_VERTICAL_WINDOW), drone.posZ - radius,
                drone.posX + radius,
                Math.min((double) WorldBounds.MAX_Y, drone.posY + DROP_VERTICAL_WINDOW), drone.posZ + radius);
        List<?> items = world.getEntitiesWithinAABB(EntityItem.class, box);
        JsonArray drops = new JsonArray();
        if (items != null) {
            for (Object candidate : items) {
                if (!(candidate instanceof EntityItem)) continue;
                EntityItem item = (EntityItem) candidate;
                if (item.isDead) continue;
                ItemStack stack = item.getEntityItem();
                if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) continue;
                if (!matchAll && !ItemClassifier.matchesBlockOrItemName(stack.getDisplayName(),
                        ItemClassifier.getItemIdentifier(stack), stack.getItemDamage(), filter)) {
                    continue;
                }
                JsonObject entry = ItemClassifier.itemStackToJson(-1, stack);
                entry.addProperty("entityId", item.getEntityId());
                entry.addProperty("x", Math.round(item.posX * 10.0) / 10.0);
                entry.addProperty("y", Math.round(item.posY * 10.0) / 10.0);
                entry.addProperty("z", Math.round(item.posZ * 10.0) / 10.0);
                entry.addProperty("dist", Math.round(drone.getDistanceToEntity(item) * 10.0) / 10.0);
                drops.add(entry);
            }
        }
        JsonObject out = ToolJson.ok();
        out.addProperty("count", drops.size());
        out.add("drops", drops);
        return out;
    }
}
