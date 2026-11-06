package ir.layercloud.cortexdrone.manager;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.manager.building.impl.BuildingManager;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.service.FlightPathfinder;
import ir.layercloud.cortexdrone.manager.navigation.impl.FlightPilot;
import ir.layercloud.cortexdrone.manager.navigation.impl.NavigationManager;
import ir.layercloud.cortexdrone.service.navigation.impl.BreadcrumbService;
import ir.layercloud.cortexdrone.manager.visuals.impl.VisualsManager;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * Single-task 20-Hz pipeline: movement, breadcrumbs, stuck recovery, item
 * pickup, lighting aura, the one active break primitive, carry/drop/deposit.
 * It contains no pre-made mining/building/farming/hunting action executor.
 */
@RequiredArgsConstructor
public class TickManager {
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final InventoryService inventoryService;
    private final BuildingManager buildingManager;
    private final VisualsManager visualsManager;
    private final NavigationManager navigationManager;
    private final FlightPilot flightPilot;
    private final BreadcrumbService breadcrumbService;

    public void tickController(DroneController ctrl) {
        Drone d = ctrl.drone;
        World world = d.worldObj;
        if (world == null || world.isRemote) return;
        ctrl.tickClock++;
        if (ctrl.followAnchorCooldown > 0) ctrl.followAnchorCooldown--;

        // OC hardware bridge is expensive (reflection + buffer top-ups); amortize every 40 ticks per drone
        boolean machineRunningOk = (ctrl.tickClock % 40 == 1) ? this.manager.ensureMachineRunningState(d) : d.isRunning();
        ctrl.orbitAngle += 0.045f;
        if (ctrl.attackCooldown > 0) ctrl.attackCooldown--;
        if (ctrl.pickupCooldown > 0) ctrl.pickupCooldown--;
        if (ctrl.repathCooldown > 0) ctrl.repathCooldown--;

        double movedSq = (d.posX - ctrl.lastX) * (d.posX - ctrl.lastX)
            + (d.posY - ctrl.lastY) * (d.posY - ctrl.lastY)
            + (d.posZ - ctrl.lastZ) * (d.posZ - ctrl.lastZ);
        ctrl.lastX = d.posX;
        ctrl.lastY = d.posY;
        ctrl.lastZ = d.posZ;

        int curBx = MathHelper.floor_double(d.posX);
        int curBy = MathHelper.floor_double(d.posY);
        int curBz = MathHelper.floor_double(d.posZ);
        // The breadcrumb service owns sampling, dedupe and loop pruning.
        this.breadcrumbService.observe(ctrl, world);

        double distToTarget = d.getDistance(d.targetX(), d.targetY(), d.targetZ());
        if (distToTarget > 0.70 && movedSq < 0.0002 && !ctrl.playerLookHold && ctrl.mode != Mode.STAY) {
            ctrl.stuckTicks++;
            if (ctrl.stuckTicks == 18 || ctrl.stuckTicks % 30 == 0) {
                ctrl.lastStuckReason = FlightPathfinder.describeNearestObstruction(world, d.posX, d.posY, d.posZ);
                // Throttle identical STUCK spam: same obstruction + same waypoint = log once per 240 ticks (12s).
                String stuckKey = ctrl.id + "|" + ctrl.lastStuckReason + "|" + (int) (d.posX * 4) + "," + (int) (d.posY * 4) + "," + (int) (d.posZ * 4)
                    + "->" + (int) ((double) d.targetX() * 4) + "," + (int) ((double) d.targetY() * 4) + "," + (int) ((double) d.targetZ() * 4);
                if (!stuckKey.equals(ctrl.lastStuckKey) || ctrl.tickClock - ctrl.lastStuckLogTick >= 240) {
                    this.logger.log("STUCK", ctrl,
                        String.format("Velocity blocked at (%.2f,%.2f,%.2f) -> wp(%.2f,%.2f,%.2f)",
                            d.posX, d.posY, d.posZ, (double) d.targetX(), (double) d.targetY(), (double) d.targetZ()),
                        "obstruction=" + ctrl.lastStuckReason);
                    ctrl.lastStuckKey = stuckKey;
                    ctrl.lastStuckLogTick = ctrl.tickClock;
                }
                FlightPathfinder.clearNearbyLeafObstruction(world, ctrl);
            }
        } else {
            ctrl.stuckTicks = 0;
        }

        if (ctrl.pickupCooldown <= 0 && (ctrl.mode == Mode.FOLLOW || ctrl.mode == Mode.MINE)) {
            ctrl.pickupCooldown = 5;
            InventoryService.suckNearbyItems(d, d.posX, d.posY, d.posZ, 3.5);
        }

        if (ctrl.autoTorch || !ctrl.lightCells.isEmpty()) {
            this.visualsManager.tickLighting(ctrl);
        }

        EntityPlayerMP owner = CortexDroneMod.findPlayer(ctrl.ownerName);
        if (ctrl.mode != Mode.FOLLOW) {
            ctrl.playerLookHold = false;
        }

        this.navigationManager.tick(ctrl);
        dispatchModeBehavior(ctrl, d, world, owner);
        this.manager.getActionRegistry().tick(ctrl);

        if (ctrl.task.isActive() && ctrl.activeScriptCount.get() == 0 && !ctrl.hasActiveTaskOrLoop(0)) {
            ctrl.task.complete();
        }

        ctrl.ledEffectTick++;
        if (!ctrl.playerLookHold && (ctrl.customLedColor != null || (ctrl.ledEffect != null && !"NONE".equalsIgnoreCase(ctrl.ledEffect)))) {
            d.lightColor_$eq(ctrl.resolveEffectiveLedColor(d.lightColor()));
            if ("DISCO".equalsIgnoreCase(ctrl.ledEffect) && ctrl.ledEffectTick % 6 == 0 && world instanceof WorldServer) {
                ((WorldServer) world).func_147487_a("note", d.posX, d.posY + 0.45, d.posZ, 2, 0.25, 0.25, 0.25, 0.5);
            }
        }

        double postTargetDist = d.getDistance(d.targetX(), d.targetY(), d.targetZ());
        double distFinalGoal = Double.isNaN(ctrl.goalX) ? postTargetDist : d.getDistance(ctrl.goalX, ctrl.goalY, ctrl.goalZ);
        boolean longRangeFlight = !ctrl.playerLookHold && ctrl.mode != Mode.STAY && distFinalGoal > 14.0;
        if (!machineRunningOk || (postTargetDist > 0.30 && movedSq < 0.0004 && !ctrl.playerLookHold) || longRangeFlight) {
            double maxSpeed = distFinalGoal > 24.0 ? 1.15 : (longRangeFlight ? 0.85 : 0.42);
            FlightPathfinder.runServerSidePhysicsStep(d, maxSpeed, ctrl);
        }
    }

    private void dispatchModeBehavior(DroneController ctrl, Drone d, World world, EntityPlayerMP owner) {
        switch (ctrl.mode) {
            case FOLLOW: {
                EntityPlayerMP target = CortexDroneMod.findPlayer(
                    ctrl.targetPlayerName != null ? ctrl.targetPlayerName : ctrl.ownerName
                );
                if (target != null && target.worldObj == world) {
                    if (FlightPathfinder.isPlayerLookingAtDrone(target, d)) {
                        ctrl.playerLookHold = true;
                        ctrl.goalX = d.posX;
                        ctrl.goalY = d.posY;
                        ctrl.goalZ = d.posZ;
                        FlightPathfinder.setDroneFlightTarget(d, d.posX, d.posY, d.posZ, 0.12f);
                        d.motionX *= 0.25;
                        d.motionY *= 0.25;
                        d.motionZ *= 0.25;
                        d.lightColor_$eq(0x55FF55);
                        d.statusText_$eq("Ready\n[Right-Click]");
                        break;
                    }
                    ctrl.playerLookHold = false;

                    double anchorPlayerDist = Math.sqrt(
                        (ctrl.anchorX - target.posX) * (ctrl.anchorX - target.posX)
                            + (ctrl.anchorY - (target.posY + 1.15)) * (ctrl.anchorY - (target.posY + 1.15))
                            + (ctrl.anchorZ - target.posZ) * (ctrl.anchorZ - target.posZ)
                    );
                    boolean anchorPassable = ItemClassifier.isPassableVoxel(
                        world,
                        MathHelper.floor_double(ctrl.anchorX),
                        MathHelper.floor_double(ctrl.anchorY),
                        MathHelper.floor_double(ctrl.anchorZ)
                    );

                    // Anchor solve is expensive (raycast walk + candidate scan); amortize over 6 ticks and
                    // only recompute when the player meaningfully moved or the anchor became blocked — never
                    // when the drone simply drifted a little inside the dead-band.
                    boolean needReanchor = !ctrl.hasAnchor || anchorPlayerDist > 3.2 || anchorPlayerDist < 1.3 || !anchorPassable;
                    if (needReanchor && ctrl.followAnchorCooldown <= 0) {
                        double[] nextAnchor = FlightPathfinder.computeStableFollowAnchor(world, target, d);
                        ctrl.anchorX = nextAnchor[0];
                        ctrl.anchorY = nextAnchor[1];
                        ctrl.anchorZ = nextAnchor[2];
                        ctrl.hasAnchor = true;
                        ctrl.followAnchorCooldown = 6;
                    }

                    double distToAnchor = d.getDistance(ctrl.anchorX, ctrl.anchorY, ctrl.anchorZ);
                    if (distToAnchor > 0.35) {
                        this.flightPilot.flyToward(ctrl, ctrl.anchorX, ctrl.anchorY, ctrl.anchorZ, 0.08f);
                    } else {
                        FlightPathfinder.setDroneFlightTarget(d, ctrl.anchorX, ctrl.anchorY, ctrl.anchorZ, 0.06f);
                        d.motionX *= 0.5;
                        d.motionY *= 0.5;
                        d.motionZ *= 0.5;
                    }
                    d.lightColor_$eq(0x00FFFF);
                    d.statusText_$eq("Following\n" + AIDroneManager.trimStatus(target.getCommandSenderName()));
                } else {
                    // Owner offline (e.g. right after a restart): the stale flight target persisted
                    // inside OC's entity NBT may now sit in solid blocks. Hover in place —
                    // grinding into the floor forever silently drains the machine and spams STUCK.
                    ctrl.playerLookHold = false;
                    FlightPathfinder.setDroneFlightTarget(d, d.posX, d.posY, d.posZ, 0.05f);
                    d.motionX *= 0.5;
                    d.motionY *= 0.5;
                    d.motionZ *= 0.5;
                    d.statusText_$eq("Owner offline\nHovering");
                    d.lightColor_$eq(0x00AA88);
                }
                break;
            }
            case STAY: {
                if (owner != null && FlightPathfinder.isPlayerLookingAtDrone(owner, d)) {
                    ctrl.playerLookHold = true;
                    d.statusText_$eq("Ready\n[Right-Click]");
                } else {
                    ctrl.playerLookHold = false;
                    d.statusText_$eq("Hovering\nStandby");
                }
                FlightPathfinder.setDroneFlightTarget(d, ctrl.goalX, ctrl.goalY, ctrl.goalZ, 0.08f);
                d.lightColor_$eq(0x0088FF);
                break;
            }
            case MINE:
                this.buildingManager.tick(ctrl, owner);
                break;
            case DROP:
                tickDropBehavior(ctrl, d, world, owner);
                break;
            case DEPOSIT:
                tickDepositBehavior(ctrl, d, world, owner);
                break;
            default:
                break;
        }
    }

    private void tickDropBehavior(DroneController ctrl, Drone d, World world, EntityPlayerMP owner) {
        if (owner == null || owner.worldObj != world) {
            ctrl.pickupCooldown = 100;
            InventoryService.dropAllDroneItemsAt(d, world, d.posX, d.posY, d.posZ);
            this.manager.setDroneMode(ctrl, Mode.STAY, "Owner offline during DROP");
            return;
        }
        double px = owner.posX;
        double py = owner.posY + 1.25;
        double pz = owner.posZ;
        this.flightPilot.flyToward(ctrl, px, py, pz, 0.1f);
        d.lightColor_$eq(0x00FFAA);
        d.statusText_$eq("Delivering\nItems");
        if (d.getDistance(px, py, pz) <= 3.5) {
            ctrl.pickupCooldown = 100;
            String filter = (ctrl.dropFilter != null && !ctrl.dropFilter.isEmpty()) ? ctrl.dropFilter : "all";
            int count = this.inventoryService.giveToPlayerInventory(ctrl, owner, filter, 9999);
            if ("all".equalsIgnoreCase(filter) || "any".equalsIgnoreCase(filter) || "*".equals(filter)) {
                count += InventoryService.dropAllDroneItemsAt(d, world, owner.posX, owner.posY + 0.5, owner.posZ);
            }
            ctrl.dropFilter = "all";
            world.playSoundAtEntity(d, "random.pop", 0.6f, 1.2f);
            this.logger.log("MINE", ctrl, "Delivered inventory items to owner", "count=" + count + " remainingMineQueue=" + ctrl.mineQueue.size());
            if (!ctrl.mineQueue.isEmpty()) {
                if (!ctrl.isScriptRunning()) {
                    this.manager.droneSay(owner, ctrl, "Delivered " + count + " item(s) into your inventory! Resuming mining (" + ctrl.mineQueue.size() + " blocks remaining).");
                }
                this.manager.setDroneMode(ctrl, Mode.MINE, "Resume mining after item drop");
            } else {
                if (!ctrl.isScriptRunning()) {
                    this.manager.droneSay(owner, ctrl, "Delivered " + count + " item(s) into your inventory.");
                }
                this.manager.setDroneMode(ctrl, Mode.FOLLOW, "Completed item delivery");
            }
        }
    }

    private void tickDepositBehavior(DroneController ctrl, Drone d, World world, EntityPlayerMP owner) {
        TileEntity chestTe = null;
        if (ctrl.targetDepositChestPos != null) {
            TileEntity explicit = world.getTileEntity(ctrl.targetDepositChestPos[0], ctrl.targetDepositChestPos[1], ctrl.targetDepositChestPos[2]);
            if (explicit instanceof IInventory) {
                chestTe = explicit;
            } else {
                ctrl.targetDepositChestPos = null;
            }
        }
        if (chestTe == null) {
            chestTe = this.inventoryService.findNearestInventoryWithSpace(world, d.posX, d.posY, d.posZ, 28, d.mainInventory());
        }
        if (chestTe == null && owner != null && owner.worldObj == world) {
            chestTe = this.inventoryService.findNearestInventoryWithSpace(world, owner.posX, owner.posY, owner.posZ, 28, d.mainInventory());
        }
        if (chestTe == null) {
            if (!ctrl.isScriptRunning() && owner != null) {
                this.manager.droneSay(owner, ctrl, "No chest with free space found within 28m; delivering items to you instead!");
            }
            this.manager.setDroneMode(ctrl, ctrl.isScriptRunning() ? Mode.FOLLOW : Mode.DROP, "No chest with space found");
            return;
        }
        double[] standoff = FlightPathfinder.findMiningStandoff(world, d, chestTe.xCoord, chestTe.yCoord, chestTe.zCoord);
        double cx = standoff != null ? standoff[0] : (chestTe.xCoord + 0.5);
        double cy = standoff != null ? standoff[1] : (chestTe.yCoord + 1.2);
        double cz = standoff != null ? standoff[2] : (chestTe.zCoord + 0.5);
        this.flightPilot.flyToward(ctrl, cx, cy, cz, 0.1f);
        d.lightColor_$eq(0xFFAA00);
        d.statusText_$eq("Depositing\nChest");

        if (d.getDistance(chestTe.xCoord + 0.5, chestTe.yCoord + 0.5, chestTe.zCoord + 0.5) <= 3.8) {
            int deposited = this.inventoryService.depositDroneItemsIntoTile(d, chestTe);
            ctrl.targetDepositChestPos = null;
            world.playSoundEffect(chestTe.xCoord + 0.5, chestTe.yCoord + 0.5, chestTe.zCoord + 0.5, "random.chestclosed", 0.5f, 1.0f);
            this.logger.log("MINE", ctrl, "Deposited items into chest",
                String.format("count=%d chest=(%d,%d,%d) remainingMineQueue=%d",
                    deposited, chestTe.xCoord, chestTe.yCoord, chestTe.zCoord, ctrl.mineQueue.size()));
            if (!ctrl.mineQueue.isEmpty() && !InventoryService.isInventoryFull(d.mainInventory())) {
                if (!ctrl.isScriptRunning() && owner != null) {
                    this.manager.droneSay(owner, ctrl, String.format("Deposited %d item(s) into chest! Resuming mining (%d blocks left).", deposited, ctrl.mineQueue.size()));
                }
                this.manager.setDroneMode(ctrl, Mode.MINE, "Resume mining after chest deposit");
            } else {
                if (!ctrl.isScriptRunning()) {
                    this.manager.droneSay(owner, ctrl, String.format("Deposited %d item(s) into chest at (%d, %d, %d).",
                        deposited, chestTe.xCoord, chestTe.yCoord, chestTe.zCoord));
                }
                this.manager.setDroneMode(ctrl, Mode.FOLLOW, "Finished chest deposit");
            }
        }
    }
}
