package ir.layercloud.cortexdrone.action.interact;

import ir.layercloud.cortexdrone.action.Action;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.inventory.ToolInspector;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.inventory.IInventory;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Primitive block/item actions only. Mining, harvesting, building, farming,
 * hunting and every other composite behavior were removed from the tool
 * surface: the AI composes them from navigate + break/place/interact/use.
 */
@RequiredArgsConstructor
public class InteractionActionService implements Action<InteractionActionContext> {
    private final InventoryService inventoryService;

    @Override
    public String category() { return "interact"; }

    @Override
    public String[] operations() { return new String[]{"interact_block", "use_item"}; }

    @Override
    public InteractionActionContext createContext(DroneController ctrl, EntityPlayerMP player,
                                                  String operation) {
        return new InteractionActionContext(ctrl, player, operation);
    }

    @Override
    public JsonObject handle(InteractionActionContext context, String m, JsonObject args) {
        DroneController ctrl = context.controller();
        EntityPlayerMP player = context.player();
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        if (ctrl == null || ctrl.drone == null || ctrl.drone.worldObj == null) {
            res.addProperty("ok", false);
            res.addProperty("error", "No active drone or world");
            return res;
        }
        if (args == null) args = new JsonObject();
        Drone d = ctrl.drone;
        World world = d.worldObj;

        if (m.equals("interact_block")) {
            if (!args.has("x") || !args.has("y") || !args.has("z")
                || args.get("x").isJsonNull() || args.get("y").isJsonNull() || args.get("z").isJsonNull()) {
                res.addProperty("ok", false);
                res.addProperty("error", "Missing x/y/z for interact_block");
                res.addProperty("activated", false);
                return res;
            }
            int x = args.get("x").getAsInt(), y = args.get("y").getAsInt(), z = args.get("z").getAsInt();
            context.targetBlock(x, y, z);
            Block b = world.getBlock(x, y, z);
            if (ItemClassifier.isLiquidBlock(b) && world.getBlockMetadata(x, y, z) == 0) {
                boolean scooped = this.inventoryService.scoopLiquidAt(world, ctrl, player, x, y, z);
                res.addProperty("activated", scooped);
                res.addProperty("scooped", scooped);
                return res;
            }
            boolean activated = false;
            net.minecraft.entity.player.EntityPlayer fakePlayer = d.player();
            if (fakePlayer == null) {
                res.addProperty("ok", false);
                res.addProperty("error", "No fake player on drone");
                res.addProperty("activated", false);
                return res;
            }
            syncFakePlayerToDrone(fakePlayer, d);
            int side = args.has("side") && !args.get("side").isJsonNull() ? args.get("side").getAsInt() : 1;
            // Exactly one action: activate the target block. Item use is a
            // separate master primitive (use_item); never chain it here.
            if (b != null) {
                activated = b.onBlockActivated(world, x, y, z, fakePlayer, side, 0.5f, 0.5f, 0.5f);
            }
            res.addProperty("activated", activated);
            res.addProperty("block", ItemClassifier.getBlockDisplayName(world.getBlock(x, y, z), world.getBlockMetadata(x, y, z)));
            return res;
        }

        if (m.equals("use_item")) {
            net.minecraft.entity.player.EntityPlayer fakePlayer = d.player();
            if (fakePlayer == null || fakePlayer.inventory == null) {
                res.addProperty("ok", false);
                res.addProperty("error", "No fake player on drone");
                res.addProperty("used", false);
                return res;
            }
            if (args.has("entityId") || args.has("attack")) {
                int entityId = args.has("entityId") ? args.get("entityId").getAsInt()
                    : args.get("attack").getAsInt();
                context.targetEntity(entityId);
                return attackEntity(ctrl, d, world, fakePlayer, args);
            }
            int slot = args.has("slot") && !args.get("slot").isJsonNull()
                ? args.get("slot").getAsInt() : fakePlayer.inventory.currentItem;
            if (args.has("item") && !args.get("item").isJsonNull()) {
                String filter = args.get("item").getAsString();
                int found = -1;
                int size = fakePlayer.inventory.getSizeInventory();
                for (int pass = 0; pass < 2 && found < 0; pass++) {
                    int from = pass == 0 ? 0 : 9;
                    int to = pass == 0 ? Math.min(9, size) : size;
                    for (int i = from; i < to; i++) {
                        net.minecraft.item.ItemStack st = fakePlayer.inventory.getStackInSlot(i);
                        if (st == null || st.getItem() == null) continue;
                        if (filter.isEmpty() || ItemClassifier.scoreBlockOrItemMatch(
                                st.getDisplayName(), ItemClassifier.getItemIdentifier(st),
                                st.getItemDamage(), filter) > 0) {
                            found = i;
                            break;
                        }
                    }
                }
                if (found < 0) {
                    res.addProperty("ok", false);
                    res.addProperty("error", "No matching item in drone inventory for filter: " + filter);
                    res.addProperty("used", false);
                    return res;
                }
                slot = found;
            }
            net.minecraft.item.ItemStack stack = slot >= 0 && slot < fakePlayer.inventory.getSizeInventory()
                ? fakePlayer.inventory.getStackInSlot(slot) : null;
            if (stack == null || stack.getItem() == null) {
                res.addProperty("ok", false);
                res.addProperty("error", "No item in slot " + slot);
                res.addProperty("used", false);
                return res;
            }
            try {
                if (slot >= 0 && slot < 9) fakePlayer.inventory.currentItem = slot;
            } catch (Throwable ignored) {
            }
            syncFakePlayerToDrone(fakePlayer, d);

            int tx, ty, tz, side;
            boolean hasTarget = args.has("x") && args.has("y") && args.has("z")
                && !args.get("x").isJsonNull() && !args.get("y").isJsonNull() && !args.get("z").isJsonNull();
            if (hasTarget) {
                tx = args.get("x").getAsInt();
                ty = args.get("y").getAsInt();
                tz = args.get("z").getAsInt();
            } else {
                tx = MathHelper.floor_double(d.posX);
                ty = MathHelper.floor_double(d.posY) - 1;
                tz = MathHelper.floor_double(d.posZ);
            }
            side = args.has("side") && !args.get("side").isJsonNull() ? args.get("side").getAsInt() : 1;

            net.minecraft.item.Item item = stack.getItem();
            int beforeCount = stack.stackSize;
            int beforeDamage = stack.getItemDamage();
            boolean used = false;
            String method = "none";
            // Exactly one item-use action: target -> onItemUse, no target ->
            // onItemRightClick. Never chain block activation or both hooks.
            if (hasTarget) {
                try {
                    used = item.onItemUse(stack, fakePlayer, world, tx, ty, tz, side, 0.5f, 0.5f, 0.5f);
                    method = "onItemUse";
                } catch (Throwable t) {
                    method = "onItemUse:error:" + t.getClass().getSimpleName();
                }
            } else {
                try {
                    net.minecraft.item.ItemStack after = item.onItemRightClick(stack, world, fakePlayer);
                    used = after != null && (after != stack
                        || after.stackSize != beforeCount
                        || after.getItemDamage() != beforeDamage);
                    method = "onItemRightClick";
                } catch (Throwable t) {
                    method = "onItemRightClick:error:" + t.getClass().getSimpleName();
                }
            }
            res.addProperty("used", used);
            res.addProperty("method", method);
            res.addProperty("slot", slot);
            res.addProperty("item", stack.getDisplayName());
            res.addProperty("x", tx);
            res.addProperty("y", ty);
            res.addProperty("z", tz);
            res.addProperty("side", side);
            Block targetBlock = world.getBlock(tx, ty, tz);
            res.addProperty("block", ItemClassifier.getBlockDisplayName(targetBlock, world.getBlockMetadata(tx, ty, tz)));
            return res;
        }

        res.addProperty("ok", false);
        res.addProperty("error", "Unhandled primitive action: " + m);
        return res;
    }

    private static JsonObject attackEntity(DroneController ctrl, Drone d, World world,
                                           EntityPlayer fakePlayer, JsonObject args) {
        JsonObject res = new JsonObject();
        int id;
        try {
            id = args.has("entityId") ? args.get("entityId").getAsInt() : args.get("attack").getAsInt();
        } catch (Throwable t) {
            res.addProperty("ok", false);
            res.addProperty("error", "entityId/attack must be an entity id");
            return res;
        }
        Entity target = world.getEntityByID(id);
        if (!(target instanceof EntityLivingBase) || target.isDead
            || ((EntityLivingBase) target).getHealth() <= 0.0f) {
            res.addProperty("ok", false);
            res.addProperty("error", "No living entity with id " + id);
            return res;
        }
        if (target instanceof EntityPlayer) {
            res.addProperty("ok", false);
            res.addProperty("error", "Refusing to attack a player");
            return res;
        }
        double dist = d.getDistance(target.posX, target.posY, target.posZ);
        if (dist > 4.5) {
            res.addProperty("ok", true);
            res.addProperty("needsMovement", true);
            res.addProperty("action", "attack");
            res.addProperty("entityId", id);
            res.addProperty("distance", Math.round(dist * 100.0) / 100.0);
            JsonObject st = new JsonObject();
            st.addProperty("x", Math.round(target.posX * 100.0) / 100.0);
            st.addProperty("y", Math.round((target.posY + 0.5) * 100.0) / 100.0);
            st.addProperty("z", Math.round(target.posZ * 100.0) / 100.0);
            res.add("standoff", st);
            return res;
        }
        if (ctrl.attackCooldown > 0) {
            res.addProperty("ok", false);
            res.addProperty("error", "Attack cooling down");
            res.addProperty("retryTicks", ctrl.attackCooldown);
            return res;
        }

        IInventory inv = d.mainInventory();
        int size = inv != null ? inv.getSizeInventory() : 0;
        int slot = args.has("slot") && !args.get("slot").isJsonNull()
            ? args.get("slot").getAsInt() : fakePlayer.inventory.currentItem;
        if (args.has("item") && !args.get("item").isJsonNull()) {
            String filter = args.get("item").getAsString();
            int found = -1;
            for (int i = 0; i < size && found < 0; i++) {
                ItemStack st = inv.getStackInSlot(i);
                if (st == null || st.getItem() == null) continue;
                if (ItemClassifier.scoreBlockOrItemMatch(
                        st.getDisplayName(), ItemClassifier.getItemIdentifier(st),
                        st.getItemDamage(), filter) > 0) {
                    found = i;
                }
            }
            if (found < 0) {
                res.addProperty("ok", false);
                res.addProperty("error", "No matching weapon/item in drone inventory for filter: " + filter);
                return res;
            }
            slot = found;
        }
        if (slot < 0 || slot >= size) {
            res.addProperty("ok", false);
            res.addProperty("error", "Invalid attack slot " + slot);
            return res;
        }
        if (slot >= 9) {
            res.addProperty("ok", false);
            res.addProperty("error", "Weapon must be in the drone's hotbar (slot 0-8)");
            return res;
        }
        fakePlayer.inventory.currentItem = slot;
        syncFakePlayerToDrone(fakePlayer, d);
        double dx = target.posX - d.posX;
        double dy = target.posY - d.posY;
        double dz = target.posZ - d.posZ;
        fakePlayer.rotationYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        fakePlayer.rotationPitch = (float) (-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);

        EntityLivingBase living = (EntityLivingBase) target;
        float before = living.getHealth();
        ItemStack weapon = inv.getStackInSlot(slot);
        float damage = ToolInspector.computeAttackDamage(inv, living, 20.0f);
        int fireSec = ToolInspector.getFireAspectSeconds(inv);
        if (fireSec > 0) living.setFire(fireSec);
        try {
            living.attackEntityFrom(DamageSource.causePlayerDamage(fakePlayer), damage);
        } catch (Throwable t) {
            res.addProperty("ok", false);
            res.addProperty("error", "Attack failed: " + t.getClass().getSimpleName());
            return res;
        }
        ctrl.attackCooldown = 4;
        float after = living.getHealth();
        res.addProperty("ok", true);
        res.addProperty("attacked", true);
        res.addProperty("entityId", id);
        res.addProperty("name", target.getCommandSenderName());
        res.addProperty("healthBefore", before);
        res.addProperty("healthAfter", after);
        res.addProperty("damage", Math.round(damage * 10.0) / 10.0);
        res.addProperty("killed", target.isDead || after <= 0.0f);
        res.addProperty("item", weapon != null && weapon.getItem() != null ? weapon.getDisplayName() : "Fist");
        res.addProperty("distance", Math.round(dist * 100.0) / 100.0);
        return res;
    }

    private static void syncFakePlayerToDrone(net.minecraft.entity.player.EntityPlayer fp, Drone d) {
        if (fp == null || d == null) return;
        try {
            fp.posX = d.posX;
            fp.posY = d.posY;
            fp.posZ = d.posZ;
            fp.dimension = d.dimension;
            fp.rotationYaw = d.rotationYaw;
            fp.rotationPitch = d.rotationPitch;
        } catch (Throwable ignored) {
        }
    }
}
