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
public class InteractionActionService implements Action {
    private final InventoryService inventoryService;

    @Override
    public String category() { return "interact"; }

    @Override
    public String[] operations() { return new String[]{"interact_block", "use_item"}; }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String m, JsonObject args) {
        JsonObject res = new JsonObject();
        res.addProperty("ok", true);
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) {
            res.addProperty("ok", false);
            res.addProperty("error", "No active drone or world");
            return res;
        }
        if (args == null) args = new JsonObject();
        Drone d = ctrl.drone;
        World world = d.field_70170_p;

        if (m.equals("interact_block")) {
            if (!args.has("x") || !args.has("y") || !args.has("z")
                || args.get("x").isJsonNull() || args.get("y").isJsonNull() || args.get("z").isJsonNull()) {
                res.addProperty("ok", false);
                res.addProperty("error", "Missing x/y/z for interact_block");
                res.addProperty("activated", false);
                return res;
            }
            int x = args.get("x").getAsInt(), y = args.get("y").getAsInt(), z = args.get("z").getAsInt();
            Block b = world.func_147439_a(x, y, z);
            if (ItemClassifier.isLiquidBlock(b) && world.func_72805_g(x, y, z) == 0) {
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
                activated = b.func_149727_a(world, x, y, z, fakePlayer, side, 0.5f, 0.5f, 0.5f);
            }
            res.addProperty("activated", activated);
            res.addProperty("block", ItemClassifier.getBlockDisplayName(world.func_147439_a(x, y, z), world.func_72805_g(x, y, z)));
            return res;
        }

        if (m.equals("use_item")) {
            net.minecraft.entity.player.EntityPlayer fakePlayer = d.player();
            if (fakePlayer == null || fakePlayer.field_71071_by == null) {
                res.addProperty("ok", false);
                res.addProperty("error", "No fake player on drone");
                res.addProperty("used", false);
                return res;
            }
            if (args.has("entityId") || args.has("attack")) {
                return attackEntity(ctrl, d, world, fakePlayer, args);
            }
            int slot = args.has("slot") && !args.get("slot").isJsonNull()
                ? args.get("slot").getAsInt() : fakePlayer.field_71071_by.field_70461_c;
            if (args.has("item") && !args.get("item").isJsonNull()) {
                String filter = args.get("item").getAsString();
                int found = -1;
                int size = fakePlayer.field_71071_by.func_70302_i_();
                for (int pass = 0; pass < 2 && found < 0; pass++) {
                    int from = pass == 0 ? 0 : 9;
                    int to = pass == 0 ? Math.min(9, size) : size;
                    for (int i = from; i < to; i++) {
                        net.minecraft.item.ItemStack st = fakePlayer.field_71071_by.func_70301_a(i);
                        if (st == null || st.func_77973_b() == null) continue;
                        if (filter.isEmpty() || ItemClassifier.scoreBlockOrItemMatch(
                                st.func_82833_r(), ItemClassifier.getItemIdentifier(st),
                                st.func_77960_j(), filter) > 0) {
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
            net.minecraft.item.ItemStack stack = slot >= 0 && slot < fakePlayer.field_71071_by.func_70302_i_()
                ? fakePlayer.field_71071_by.func_70301_a(slot) : null;
            if (stack == null || stack.func_77973_b() == null) {
                res.addProperty("ok", false);
                res.addProperty("error", "No item in slot " + slot);
                res.addProperty("used", false);
                return res;
            }
            try {
                if (slot >= 0 && slot < 9) fakePlayer.field_71071_by.field_70461_c = slot;
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
                tx = MathHelper.func_76128_c(d.field_70165_t);
                ty = MathHelper.func_76128_c(d.field_70163_u) - 1;
                tz = MathHelper.func_76128_c(d.field_70161_v);
            }
            side = args.has("side") && !args.get("side").isJsonNull() ? args.get("side").getAsInt() : 1;

            net.minecraft.item.Item item = stack.func_77973_b();
            int beforeCount = stack.field_77994_a;
            int beforeDamage = stack.func_77960_j();
            boolean used = false;
            String method = "none";
            // Exactly one item-use action: target -> onItemUse, no target ->
            // onItemRightClick. Never chain block activation or both hooks.
            if (hasTarget) {
                try {
                    used = item.func_77648_a(stack, fakePlayer, world, tx, ty, tz, side, 0.5f, 0.5f, 0.5f);
                    method = "onItemUse";
                } catch (Throwable t) {
                    method = "onItemUse:error:" + t.getClass().getSimpleName();
                }
            } else {
                try {
                    net.minecraft.item.ItemStack after = item.func_77659_a(stack, world, fakePlayer);
                    used = after != null && (after != stack
                        || after.field_77994_a != beforeCount
                        || after.func_77960_j() != beforeDamage);
                    method = "onItemRightClick";
                } catch (Throwable t) {
                    method = "onItemRightClick:error:" + t.getClass().getSimpleName();
                }
            }
            res.addProperty("used", used);
            res.addProperty("method", method);
            res.addProperty("slot", slot);
            res.addProperty("item", stack.func_82833_r());
            res.addProperty("x", tx);
            res.addProperty("y", ty);
            res.addProperty("z", tz);
            res.addProperty("side", side);
            Block targetBlock = world.func_147439_a(tx, ty, tz);
            res.addProperty("block", ItemClassifier.getBlockDisplayName(targetBlock, world.func_72805_g(tx, ty, tz)));
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
        Entity target = world.func_73045_a(id);
        if (!(target instanceof EntityLivingBase) || target.field_70128_L
            || ((EntityLivingBase) target).func_110143_aJ() <= 0.0f) {
            res.addProperty("ok", false);
            res.addProperty("error", "No living entity with id " + id);
            return res;
        }
        if (target instanceof EntityPlayer) {
            res.addProperty("ok", false);
            res.addProperty("error", "Refusing to attack a player");
            return res;
        }
        double dist = d.func_70011_f(target.field_70165_t, target.field_70163_u, target.field_70161_v);
        if (dist > 4.5) {
            res.addProperty("ok", true);
            res.addProperty("needsMovement", true);
            res.addProperty("action", "attack");
            res.addProperty("entityId", id);
            res.addProperty("distance", Math.round(dist * 100.0) / 100.0);
            JsonObject st = new JsonObject();
            st.addProperty("x", Math.round(target.field_70165_t * 100.0) / 100.0);
            st.addProperty("y", Math.round((target.field_70163_u + 0.5) * 100.0) / 100.0);
            st.addProperty("z", Math.round(target.field_70161_v * 100.0) / 100.0);
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
        int size = inv != null ? inv.func_70302_i_() : 0;
        int slot = args.has("slot") && !args.get("slot").isJsonNull()
            ? args.get("slot").getAsInt() : fakePlayer.field_71071_by.field_70461_c;
        if (args.has("item") && !args.get("item").isJsonNull()) {
            String filter = args.get("item").getAsString();
            int found = -1;
            for (int i = 0; i < size && found < 0; i++) {
                ItemStack st = inv.func_70301_a(i);
                if (st == null || st.func_77973_b() == null) continue;
                if (ItemClassifier.scoreBlockOrItemMatch(
                        st.func_82833_r(), ItemClassifier.getItemIdentifier(st),
                        st.func_77960_j(), filter) > 0) {
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
        fakePlayer.field_71071_by.field_70461_c = slot;
        syncFakePlayerToDrone(fakePlayer, d);
        double dx = target.field_70165_t - d.field_70165_t;
        double dy = target.field_70163_u - d.field_70163_u;
        double dz = target.field_70161_v - d.field_70161_v;
        fakePlayer.field_70177_z = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        fakePlayer.field_70125_A = (float) (-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);

        EntityLivingBase living = (EntityLivingBase) target;
        float before = living.func_110143_aJ();
        ItemStack weapon = inv.func_70301_a(slot);
        float damage = ToolInspector.computeAttackDamage(inv, living, 20.0f);
        int fireSec = ToolInspector.getFireAspectSeconds(inv);
        if (fireSec > 0) living.func_70015_d(fireSec);
        try {
            living.func_70097_a(DamageSource.func_76365_a(fakePlayer), damage);
        } catch (Throwable t) {
            res.addProperty("ok", false);
            res.addProperty("error", "Attack failed: " + t.getClass().getSimpleName());
            return res;
        }
        ctrl.attackCooldown = 4;
        float after = living.func_110143_aJ();
        res.addProperty("ok", true);
        res.addProperty("attacked", true);
        res.addProperty("entityId", id);
        res.addProperty("name", target.func_70005_c_());
        res.addProperty("healthBefore", before);
        res.addProperty("healthAfter", after);
        res.addProperty("damage", Math.round(damage * 10.0) / 10.0);
        res.addProperty("killed", target.field_70128_L || after <= 0.0f);
        res.addProperty("item", weapon != null && weapon.func_77973_b() != null ? weapon.func_82833_r() : "Fist");
        res.addProperty("distance", Math.round(dist * 100.0) / 100.0);
        return res;
    }

    private static void syncFakePlayerToDrone(net.minecraft.entity.player.EntityPlayer fp, Drone d) {
        if (fp == null || d == null) return;
        try {
            fp.field_70165_t = d.field_70165_t;
            fp.field_70163_u = d.field_70163_u;
            fp.field_70161_v = d.field_70161_v;
            fp.field_71093_bK = d.field_71093_bK;
            fp.field_70177_z = d.field_70177_z;
            fp.field_70125_A = d.field_70125_A;
        } catch (Throwable ignored) {
        }
    }
}
