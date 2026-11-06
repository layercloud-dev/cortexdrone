package ir.layercloud.cortexdrone.action.interact;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.action.Action;
import ir.layercloud.cortexdrone.action.ActionArgs;
import ir.layercloud.cortexdrone.action.ActionJson;
import ir.layercloud.cortexdrone.action.ActionService;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.inventory.ToolInspector;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Long-lived interaction service. It owns the block/item/attack capabilities
 * and creates one single-use {@link Action} per request.
 */
public final class InteractionService extends ActionService<InteractionContext> {
    private final InventoryService inventoryService;

    public InteractionService(InventoryService inventoryService) {
        super("interact", "interact_block", "use_item");
        this.inventoryService = inventoryService;
    }

    @Override
    protected InteractionContext createContext(DroneController controller, EntityPlayerMP player,
                                               String operation) {
        return new InteractionContext(controller, player, operation);
    }

    @Override
    protected Action<InteractionContext> createAction(InteractionContext context, String operation,
                                                      JsonObject args) {
        InteractionRequest request = parse(args);
        if ("interact_block".equals(operation)) {
            if (!request.isCoordinates()) {
                return ignored -> context.result(InteractionResult.failure(
                    "Missing x/y/z for interact_block", "activated", false));
            }
            return new InteractBlockAction(this, request);
        }
        if ("use_item".equals(operation)) return new UseItemAction(this, request);
        throw new IllegalArgumentException("Unsupported interaction action: " + operation);
    }

    @Override
    protected JsonObject serialize(InteractionContext context) {
        InteractionResult result = context.result();
        if (result == null) return ActionJson.error("Interaction action produced no result");

        JsonObject response = result.isOk()
            ? ActionJson.ok()
            : ActionJson.error(result.getError());
        ActionJson.addAll(response, result.getFields());
        if (result.getStandoff() != null) {
            JsonObject standoff = new JsonObject();
            standoff.addProperty("x", Math.round(result.getStandoff()[0] * 100.0) / 100.0);
            standoff.addProperty("y", Math.round(result.getStandoff()[1] * 100.0) / 100.0);
            standoff.addProperty("z", Math.round(result.getStandoff()[2] * 100.0) / 100.0);
            response.add("standoff", standoff);
        }
        return response;
    }

    private static InteractionRequest parse(JsonObject args) {
        boolean coordinates = ActionArgs.has(args, "x")
            && ActionArgs.has(args, "y")
            && ActionArgs.has(args, "z");
        boolean entityRequested = ActionArgs.has(args, "entityId") || ActionArgs.has(args, "attack");
        int entityId = -1;
        if (entityRequested) {
            try {
                entityId = ActionArgs.integer(args, "entityId",
                    ActionArgs.integer(args, "attack", -1));
            } catch (RuntimeException ignored) {
                entityId = -1;
            }
        }
        return InteractionRequest.builder()
            .coordinates(coordinates)
            .x(coordinates ? args.get("x").getAsInt() : 0)
            .y(coordinates ? args.get("y").getAsInt() : 0)
            .z(coordinates ? args.get("z").getAsInt() : 0)
            .side(ActionArgs.integer(args, "side", 1))
            .item(ActionArgs.string(args, "item", null))
            .slot(ActionArgs.has(args, "slot") ? Integer.valueOf(args.get("slot").getAsInt()) : null)
            .entityId(entityId)
            .entityRequested(entityRequested)
            .build();
    }

    InteractionResult interactBlock(InteractionContext context, InteractionRequest request) {
        DroneController controller = context.controller();
        EntityPlayerMP player = context.player();
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return InteractionResult.failure("No active drone or world", "activated", false);
        }

        Drone drone = controller.drone;
        World world = drone.worldObj;
        int x = request.getX();
        int y = request.getY();
        int z = request.getZ();
        Block block = world.getBlock(x, y, z);

        if (ItemClassifier.isLiquidBlock(block) && world.getBlockMetadata(x, y, z) == 0) {
            boolean scooped = this.inventoryService.scoopLiquidAt(world, controller, player, x, y, z);
            return InteractionResult.success("activated", scooped, "scooped", scooped);
        }

        EntityPlayer fakePlayer = drone.player();
        if (fakePlayer == null) {
            return InteractionResult.failure("No fake player on drone", "activated", false);
        }
        syncFakePlayerToDrone(fakePlayer, drone);

        boolean activated = block != null && block.onBlockActivated(
            world, x, y, z, fakePlayer, request.getSide(), 0.5f, 0.5f, 0.5f);
        return InteractionResult.success(
            "activated", activated,
            "block", ItemClassifier.getBlockDisplayName(
                world.getBlock(x, y, z), world.getBlockMetadata(x, y, z)));
    }

    InteractionResult useItem(InteractionContext context, InteractionRequest request) {
        DroneController controller = context.controller();
        if (controller == null || controller.drone == null || controller.drone.worldObj == null) {
            return InteractionResult.failure("No active drone or world", "used", false);
        }

        Drone drone = controller.drone;
        World world = drone.worldObj;
        EntityPlayer fakePlayer = drone.player();
        if (fakePlayer == null || fakePlayer.inventory == null) {
            return InteractionResult.failure("No fake player on drone", "used", false);
        }

        if (request.hasEntity()) {
            return attackEntity(controller, drone, world, fakePlayer, request);
        }

        int slot = request.hasSlot()
            ? request.getSlot()
            : fakePlayer.inventory.currentItem;
        String filter = request.getItem();
        if (filter != null) {
            int found = -1;
            int size = fakePlayer.inventory.getSizeInventory();
            for (int pass = 0; pass < 2 && found < 0; pass++) {
                int from = pass == 0 ? 0 : 9;
                int to = pass == 0 ? Math.min(9, size) : size;
                for (int i = from; i < to; i++) {
                    ItemStack candidate = fakePlayer.inventory.getStackInSlot(i);
                    if (candidate == null || candidate.getItem() == null) continue;
                    if (filter.isEmpty() || ItemClassifier.scoreBlockOrItemMatch(
                        candidate.getDisplayName(), ItemClassifier.getItemIdentifier(candidate),
                        candidate.getItemDamage(), filter) > 0) {
                        found = i;
                        break;
                    }
                }
            }
            if (found < 0) {
                return InteractionResult.failure(
                    "No matching item in drone inventory for filter: " + filter, "used", false);
            }
            slot = found;
        }

        ItemStack stack = slot >= 0 && slot < fakePlayer.inventory.getSizeInventory()
            ? fakePlayer.inventory.getStackInSlot(slot) : null;
        if (stack == null || stack.getItem() == null) {
            return InteractionResult.failure("No item in slot " + slot, "used", false);
        }

        if (slot < 9) fakePlayer.inventory.currentItem = slot;
        syncFakePlayerToDrone(fakePlayer, drone);

        int x;
        int y;
        int z;
        if (request.isCoordinates()) {
            x = request.getX();
            y = request.getY();
            z = request.getZ();
        } else {
            x = MathHelper.floor_double(drone.posX);
            y = MathHelper.floor_double(drone.posY) - 1;
            z = MathHelper.floor_double(drone.posZ);
        }

        Item item = stack.getItem();
        int beforeCount = stack.stackSize;
        int beforeDamage = stack.getItemDamage();
        boolean used = false;
        String method = "none";
        if (request.isCoordinates()) {
            try {
                used = item.onItemUse(stack, fakePlayer, world, x, y, z,
                    request.getSide(), 0.5f, 0.5f, 0.5f);
                method = "onItemUse";
            } catch (Throwable failure) {
                method = "onItemUse:error:" + failure.getClass().getSimpleName();
            }
        } else {
            try {
                ItemStack after = item.onItemRightClick(stack, world, fakePlayer);
                used = after != null && (after != stack
                    || after.stackSize != beforeCount
                    || after.getItemDamage() != beforeDamage);
                method = "onItemRightClick";
            } catch (Throwable failure) {
                method = "onItemRightClick:error:" + failure.getClass().getSimpleName();
            }
        }

        Block targetBlock = world.getBlock(x, y, z);
        return InteractionResult.success(
            "used", used,
            "method", method,
            "slot", slot,
            "item", stack.getDisplayName(),
            "x", x,
            "y", y,
            "z", z,
            "side", request.getSide(),
            "block", ItemClassifier.getBlockDisplayName(
                targetBlock, world.getBlockMetadata(x, y, z)));
    }

    private static InteractionResult attackEntity(DroneController controller, Drone drone,
                                                  World world, EntityPlayer fakePlayer,
                                                  InteractionRequest request) {
        int id = request.getEntityId();
        Entity target = world.getEntityByID(id);
        if (!(target instanceof EntityLivingBase) || target.isDead
            || ((EntityLivingBase) target).getHealth() <= 0.0f) {
            return InteractionResult.failure("No living entity with id " + id);
        }
        if (target instanceof EntityPlayer) {
            return InteractionResult.failure("Refusing to attack a player");
        }

        double distance = drone.getDistance(target.posX, target.posY, target.posZ);
        if (distance > 4.5) {
            return InteractionResult.movement(
                new double[]{target.posX, target.posY + 0.5, target.posZ},
                "needsMovement", true,
                "action", "attack",
                "entityId", id,
                "distance", Math.round(distance * 100.0) / 100.0);
        }
        if (controller.attackCooldown > 0) {
            return InteractionResult.failure("Attack cooling down", "retryTicks", controller.attackCooldown);
        }

        IInventory inventory = drone.mainInventory();
        int size = inventory != null ? inventory.getSizeInventory() : 0;
        int slot = request.hasSlot() ? request.getSlot() : fakePlayer.inventory.currentItem;
        String filter = request.getItem();
        if (filter != null) {
            int found = -1;
            for (int i = 0; i < size && found < 0; i++) {
                ItemStack candidate = inventory.getStackInSlot(i);
                if (candidate == null || candidate.getItem() == null) continue;
                if (ItemClassifier.scoreBlockOrItemMatch(
                    candidate.getDisplayName(), ItemClassifier.getItemIdentifier(candidate),
                    candidate.getItemDamage(), filter) > 0) {
                    found = i;
                }
            }
            if (found < 0) {
                return InteractionResult.failure(
                    "No matching weapon/item in drone inventory for filter: " + filter);
            }
            slot = found;
        }
        if (slot < 0 || slot >= size) {
            return InteractionResult.failure("Invalid attack slot " + slot);
        }
        if (slot >= 9) {
            return InteractionResult.failure("Weapon must be in the drone's hotbar (slot 0-8)");
        }

        fakePlayer.inventory.currentItem = slot;
        syncFakePlayerToDrone(fakePlayer, drone);
        double dx = target.posX - drone.posX;
        double dy = target.posY - drone.posY;
        double dz = target.posZ - drone.posZ;
        fakePlayer.rotationYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        fakePlayer.rotationPitch = (float) (-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);

        EntityLivingBase living = (EntityLivingBase) target;
        float before = living.getHealth();
        ItemStack weapon = inventory.getStackInSlot(slot);
        float damage = ToolInspector.computeAttackDamage(inventory, living, 20.0f);
        int fireSeconds = ToolInspector.getFireAspectSeconds(inventory);
        if (fireSeconds > 0) living.setFire(fireSeconds);
        try {
            living.attackEntityFrom(DamageSource.causePlayerDamage(fakePlayer), damage);
        } catch (Throwable failure) {
            return InteractionResult.failure("Attack failed: " + failure.getClass().getSimpleName());
        }

        controller.attackCooldown = 4;
        float after = living.getHealth();
        return InteractionResult.success(
            "attacked", true,
            "entityId", id,
            "name", target.getCommandSenderName(),
            "healthBefore", before,
            "healthAfter", after,
            "damage", Math.round(damage * 10.0) / 10.0,
            "killed", target.isDead || after <= 0.0f,
            "item", weapon != null && weapon.getItem() != null ? weapon.getDisplayName() : "Fist",
            "distance", Math.round(distance * 100.0) / 100.0);
    }

    private static void syncFakePlayerToDrone(EntityPlayer fakePlayer, Drone drone) {
        if (fakePlayer == null || drone == null) return;
        try {
            fakePlayer.posX = drone.posX;
            fakePlayer.posY = drone.posY;
            fakePlayer.posZ = drone.posZ;
            fakePlayer.dimension = drone.dimension;
            fakePlayer.rotationYaw = drone.rotationYaw;
            fakePlayer.rotationPitch = drone.rotationPitch;
        } catch (Throwable ignored) {
        }
    }
}
