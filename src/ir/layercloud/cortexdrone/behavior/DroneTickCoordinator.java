package ir.layercloud.cortexdrone.behavior;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.inventory.SmeltingService;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.FlightPathfinder;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import li.cil.oc.common.entity.Drone;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * Coordinates the 20-Hz server tick loop for each active DroneController:
 * machine state maintenance, breadcrumb recording, velocity stuck recovery,
 * item vacuuming, auto-torch, mode state machine dispatch, and 20-FPS LED animations.
 */
public class DroneTickCoordinator {
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final FlightPathfinder pathfinder;
    private final InventoryService inventoryService;
    private final SmeltingService smeltingService;
    private final MiningService miningService;
    private final BuildingService buildingService;
    private final CombatService combatService;

    public DroneTickCoordinator(
        AIDroneManager manager,
        DroneLogger logger,
        FlightPathfinder pathfinder,
        InventoryService inventoryService,
        SmeltingService smeltingService,
        MiningService miningService,
        BuildingService buildingService,
        CombatService combatService
    ) {
        this.manager = manager;
        this.logger = logger;
        this.pathfinder = pathfinder;
        this.inventoryService = inventoryService;
        this.smeltingService = smeltingService;
        this.miningService = miningService;
        this.buildingService = buildingService;
        this.combatService = combatService;
    }

    public void tickController(DroneController ctrl) {
        Drone d = ctrl.drone;
        World world = d.field_70170_p;
        if (world == null || world.field_72995_K) return;
        ctrl.tickClock++;
        if (ctrl.followAnchorCooldown > 0) ctrl.followAnchorCooldown--;
        if (ctrl.breadcrumbCooldown > 0) ctrl.breadcrumbCooldown--;

        FlightPathfinder.ensureChunkLoaded(world, d.field_70165_t, d.field_70161_v);
        // OC hardware bridge is expensive (reflection + buffer top-ups); amortize every 40 ticks per drone
        boolean machineRunningOk = (ctrl.tickClock % 40 == 1) ? this.manager.ensureMachineRunningState(d) : d.isRunning();
        ctrl.orbitAngle += 0.045f;
        if (ctrl.attackCooldown > 0) ctrl.attackCooldown--;
        if (ctrl.torchCooldown > 0) ctrl.torchCooldown--;
        if (ctrl.pickupCooldown > 0) ctrl.pickupCooldown--;
        if (ctrl.repathCooldown > 0) ctrl.repathCooldown--;

        double movedSq = (d.field_70165_t - ctrl.lastX) * (d.field_70165_t - ctrl.lastX)
            + (d.field_70163_u - ctrl.lastY) * (d.field_70163_u - ctrl.lastY)
            + (d.field_70161_v - ctrl.lastZ) * (d.field_70161_v - ctrl.lastZ);
        ctrl.lastX = d.field_70165_t;
        ctrl.lastY = d.field_70163_u;
        ctrl.lastZ = d.field_70161_v;

        int curBx = MathHelper.func_76128_c(d.field_70165_t);
        int curBy = MathHelper.func_76128_c(d.field_70163_u);
        int curBz = MathHelper.func_76128_c(d.field_70161_v);
        // Breadcrumb + sky-raycast is the hottest per-tick cost; throttle to every 4 ticks (still lossless,
        // because recordBreadcrumb already dedupes |dx|+|dy|+|dz| < 2 block moves)
        if (ctrl.breadcrumbCooldown <= 0 && ItemClassifier.isPassableVoxel(world, curBx, curBy, curBz)) {
            ctrl.breadcrumbCooldown = 4;
            ctrl.recordBreadcrumb(curBx, curBy, curBz, world.func_72937_j(curBx, curBy, curBz));
        }

        double distToTarget = d.func_70011_f(d.targetX(), d.targetY(), d.targetZ());
        if (distToTarget > 0.70 && movedSq < 0.0002 && !ctrl.playerLookHold && ctrl.mode != Mode.STAY) {
            ctrl.stuckTicks++;
            if (ctrl.stuckTicks == 18 || ctrl.stuckTicks % 30 == 0) {
                ctrl.lastStuckReason = FlightPathfinder.describeNearestObstruction(world, d.field_70165_t, d.field_70163_u, d.field_70161_v);
                // Throttle identical STUCK spam: same obstruction + same waypoint = log once per 240 ticks (12s).
                String stuckKey = ctrl.id + "|" + ctrl.lastStuckReason + "|" + (int) (d.field_70165_t * 4) + "," + (int) (d.field_70163_u * 4) + "," + (int) (d.field_70161_v * 4)
                    + "->" + (int) ((double) d.targetX() * 4) + "," + (int) ((double) d.targetY() * 4) + "," + (int) ((double) d.targetZ() * 4);
                if (!stuckKey.equals(ctrl.lastStuckKey) || ctrl.tickClock - ctrl.lastStuckLogTick >= 240) {
                    this.logger.log("STUCK", ctrl,
                        String.format("Velocity blocked at (%.2f,%.2f,%.2f) -> wp(%.2f,%.2f,%.2f)",
                            d.field_70165_t, d.field_70163_u, d.field_70161_v, (double) d.targetX(), (double) d.targetY(), (double) d.targetZ()),
                        "obstruction=" + ctrl.lastStuckReason);
                    ctrl.lastStuckKey = stuckKey;
                    ctrl.lastStuckLogTick = ctrl.tickClock;
                }
                FlightPathfinder.clearNearbyLeafObstruction(world, ctrl);
                if (ctrl.pathIndex < ctrl.pathWaypoints.size() - 1) {
                    ctrl.pathIndex++;
                } else {
                    ctrl.repathCooldown = 0;
                    ctrl.pathWaypoints.clear();
                }
                int[] open = FlightPathfinder.findNearestPassableVoxel(world, curBx, curBy, curBz, 2);
                if (open != null) {
                    double ox = (open[0] + 0.5) - d.field_70165_t;
                    double oy = (open[1] + 0.65) - d.field_70163_u;
                    double oz = (open[2] + 0.5) - d.field_70161_v;
                    double olen = Math.sqrt(ox * ox + oy * oy + oz * oz);
                    if (olen > 0.01) {
                        d.field_70159_w += (ox / olen) * 0.14;
                        d.field_70181_x += (oy / olen) * 0.14 + 0.05;
                        d.field_70179_y += (oz / olen) * 0.14;
                    }
                }
            }
        } else {
            ctrl.stuckTicks = 0;
        }

        if (ctrl.pickupCooldown <= 0 && (ctrl.mode == Mode.FOLLOW || ctrl.mode == Mode.GUARD || ctrl.mode == Mode.PATROL || ctrl.mode == Mode.MINE)) {
            ctrl.pickupCooldown = 5;
            InventoryService.suckNearbyItems(d, d.field_70165_t, d.field_70163_u, d.field_70161_v, 3.5);
        }

        if (ctrl.autoTorch && ctrl.torchCooldown <= 0) {
            ctrl.torchCooldown = 15;
            this.buildingService.tryPlaceAutoTorch(ctrl);
        }

        EntityPlayerMP owner = CortexDroneMod.findPlayer(ctrl.ownerName);
        if (ctrl.mode != Mode.FOLLOW && ctrl.mode != Mode.GUARD && ctrl.mode != Mode.PATROL) {
            ctrl.playerLookHold = false;
        }

        dispatchModeBehavior(ctrl, d, world, owner);

        ctrl.ledEffectTick++;
        if (!ctrl.playerLookHold && (ctrl.customLedColor != null || (ctrl.ledEffect != null && !"NONE".equalsIgnoreCase(ctrl.ledEffect)))) {
            d.lightColor_$eq(ctrl.resolveEffectiveLedColor(d.lightColor()));
            if ("DISCO".equalsIgnoreCase(ctrl.ledEffect) && ctrl.ledEffectTick % 6 == 0 && world instanceof WorldServer) {
                ((WorldServer) world).func_147487_a("note", d.field_70165_t, d.field_70163_u + 0.45, d.field_70161_v, 2, 0.25, 0.25, 0.25, 0.5);
            }
        }

        double postTargetDist = d.func_70011_f(d.targetX(), d.targetY(), d.targetZ());
        double distFinalGoal = Double.isNaN(ctrl.goalX) ? postTargetDist : d.func_70011_f(ctrl.goalX, ctrl.goalY, ctrl.goalZ);
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
                if (target != null && target.field_70170_p == world) {
                    if (FlightPathfinder.isPlayerLookingAtDrone(target, d)) {
                        ctrl.playerLookHold = true;
                        ctrl.goalX = d.field_70165_t;
                        ctrl.goalY = d.field_70163_u;
                        ctrl.goalZ = d.field_70161_v;
                        FlightPathfinder.setDroneFlightTarget(d, d.field_70165_t, d.field_70163_u, d.field_70161_v, 0.12f);
                        d.field_70159_w *= 0.25;
                        d.field_70181_x *= 0.25;
                        d.field_70179_y *= 0.25;
                        d.lightColor_$eq(0x55FF55);
                        d.statusText_$eq("Ready\n[Right-Click]");
                        break;
                    }
                    ctrl.playerLookHold = false;

                    double anchorPlayerDist = Math.sqrt(
                        (ctrl.anchorX - target.field_70165_t) * (ctrl.anchorX - target.field_70165_t)
                            + (ctrl.anchorY - (target.field_70163_u + 1.15)) * (ctrl.anchorY - (target.field_70163_u + 1.15))
                            + (ctrl.anchorZ - target.field_70161_v) * (ctrl.anchorZ - target.field_70161_v)
                    );
                    boolean anchorPassable = ItemClassifier.isPassableVoxel(
                        world,
                        MathHelper.func_76128_c(ctrl.anchorX),
                        MathHelper.func_76128_c(ctrl.anchorY),
                        MathHelper.func_76128_c(ctrl.anchorZ)
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

                    double distToAnchor = d.func_70011_f(ctrl.anchorX, ctrl.anchorY, ctrl.anchorZ);
                    if (distToAnchor > 0.35) {
                        this.pathfinder.navigateToward(ctrl, ctrl.anchorX, ctrl.anchorY, ctrl.anchorZ, 0.08f);
                    } else {
                        FlightPathfinder.setDroneFlightTarget(d, ctrl.anchorX, ctrl.anchorY, ctrl.anchorZ, 0.06f);
                        d.field_70159_w *= 0.5;
                        d.field_70181_x *= 0.5;
                        d.field_70179_y *= 0.5;
                    }
                    d.lightColor_$eq(0x00FFFF);
                    d.statusText_$eq("Following\n" + AIDroneManager.trimStatus(target.func_70005_c_()));
                } else {
                    // Owner offline (e.g. right after a restart): the stale flight target persisted
                    // inside OC's entity NBT may now sit in solid blocks. Hover in place —
                    // grinding into the floor forever silently drains the machine and spams STUCK.
                    ctrl.playerLookHold = false;
                    FlightPathfinder.setDroneFlightTarget(d, d.field_70165_t, d.field_70163_u, d.field_70161_v, 0.05f);
                    d.field_70159_w *= 0.5;
                    d.field_70181_x *= 0.5;
                    d.field_70179_y *= 0.5;
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
            case GOTO: {
                this.pathfinder.navigateToward(ctrl, ctrl.goalX, ctrl.goalY, ctrl.goalZ, 0.1f);
                double dist = d.func_70011_f(ctrl.goalX, ctrl.goalY, ctrl.goalZ);
                if (dist <= 0.85) {
                    this.manager.setDroneMode(ctrl, Mode.STAY, "Arrived at GOTO destination");
                    d.lightColor_$eq(0x00FFAA);
                    d.statusText_$eq("Arrived\n" + (int) Math.floor(ctrl.goalX) + "," + (int) Math.floor(ctrl.goalZ));
                    if (!ctrl.isScriptRunning() && owner != null) {
                        this.manager.droneSay(owner, ctrl, String.format("Arrived at (%.1f, %.1f, %.1f).", ctrl.goalX, ctrl.goalY, ctrl.goalZ));
                    }
                } else {
                    d.lightColor_$eq(0xFFCC00);
                    d.statusText_$eq("Goto " + (int) dist + "m\n" + (int) ctrl.goalX + "," + (int) ctrl.goalY + "," + (int) ctrl.goalZ);
                }
                break;
            }
            case GUARD:
            case PATROL:
                this.combatService.tickGuardOrPatrolBehavior(ctrl);
                break;
            case MINE:
                this.miningService.tickMiningBehavior(ctrl, owner);
                break;
            case HUNT:
                this.combatService.tickHuntBehavior(ctrl, owner);
                break;
            case BUILD:
                this.buildingService.tickBuildBehavior(ctrl, owner);
                break;
            case SCOOP:
                this.inventoryService.tickScoopBehavior(ctrl, owner);
                break;
            case SMELT:
                this.smeltingService.tickSmeltBehavior(ctrl, owner);
                break;
            case MACHINE:
                try {
                    this.inventoryService.getMachineCraftExecutor().tickMachineCraftBehavior(ctrl, owner);
                } catch (Throwable t) {
                    this.logger.log("ERROR", ctrl, "Exception in tickMachineCraftBehavior: " + t.toString(),
                        t.getStackTrace().length > 0 ? t.getStackTrace()[0].toString() : "");
                    this.manager.setDroneMode(ctrl, Mode.FOLLOW, "MachineCraft tick error");
                }
                break;
            case CARRY:
                this.pathfinder.tickCarryBehavior(ctrl, owner);
                break;
            case DROP:
                tickDropBehavior(ctrl, d, world, owner);
                break;
            case DEPOSIT:
                tickDepositBehavior(ctrl, d, world, owner);
                break;
        }
    }

    private void tickDropBehavior(DroneController ctrl, Drone d, World world, EntityPlayerMP owner) {
        if (owner == null || owner.field_70170_p != world) {
            ctrl.pickupCooldown = 100;
            InventoryService.dropAllDroneItemsAt(d, world, d.field_70165_t, d.field_70163_u, d.field_70161_v);
            this.manager.setDroneMode(ctrl, Mode.STAY, "Owner offline during DROP");
            return;
        }
        double px = owner.field_70165_t;
        double py = owner.field_70163_u + 1.25;
        double pz = owner.field_70161_v;
        this.pathfinder.navigateToward(ctrl, px, py, pz, 0.1f);
        d.lightColor_$eq(0x00FFAA);
        d.statusText_$eq("Delivering\nItems");
        if (d.func_70011_f(px, py, pz) <= 3.5) {
            ctrl.pickupCooldown = 100;
            String filter = (ctrl.dropFilter != null && !ctrl.dropFilter.isEmpty()) ? ctrl.dropFilter : "all";
            int count = this.inventoryService.giveToPlayerInventory(ctrl, owner, filter, 9999);
            if ("all".equalsIgnoreCase(filter) || "any".equalsIgnoreCase(filter) || "*".equals(filter)) {
                count += InventoryService.dropAllDroneItemsAt(d, world, owner.field_70165_t, owner.field_70163_u + 0.5, owner.field_70161_v);
            }
            ctrl.dropFilter = "all";
            world.func_72956_a(d, "random.pop", 0.6f, 1.2f);
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
            TileEntity explicit = world.func_147438_o(ctrl.targetDepositChestPos[0], ctrl.targetDepositChestPos[1], ctrl.targetDepositChestPos[2]);
            if (explicit instanceof IInventory) {
                chestTe = explicit;
            } else {
                ctrl.targetDepositChestPos = null;
            }
        }
        if (chestTe == null) {
            chestTe = this.inventoryService.findNearestInventoryWithSpace(world, d.field_70165_t, d.field_70163_u, d.field_70161_v, 28, d.mainInventory());
        }
        if (chestTe == null && owner != null && owner.field_70170_p == world) {
            chestTe = this.inventoryService.findNearestInventoryWithSpace(world, owner.field_70165_t, owner.field_70163_u, owner.field_70161_v, 28, d.mainInventory());
        }
        if (chestTe == null) {
            if (!ctrl.isScriptRunning() && owner != null) {
                this.manager.droneSay(owner, ctrl, "No chest with free space found within 28m; delivering items to you instead!");
            }
            this.manager.setDroneMode(ctrl, ctrl.isScriptRunning() ? Mode.FOLLOW : Mode.DROP, "No chest with space found");
            return;
        }
        double[] standoff = FlightPathfinder.findMiningStandoff(world, d, chestTe.field_145851_c, chestTe.field_145848_d, chestTe.field_145849_e);
        double cx = standoff != null ? standoff[0] : (chestTe.field_145851_c + 0.5);
        double cy = standoff != null ? standoff[1] : (chestTe.field_145848_d + 1.2);
        double cz = standoff != null ? standoff[2] : (chestTe.field_145849_e + 0.5);
        this.pathfinder.navigateToward(ctrl, cx, cy, cz, 0.1f);
        d.lightColor_$eq(0xFFAA00);
        d.statusText_$eq("Depositing\nChest");

        if (d.func_70011_f(chestTe.field_145851_c + 0.5, chestTe.field_145848_d + 0.5, chestTe.field_145849_e + 0.5) <= 3.8) {
            int deposited = this.inventoryService.depositDroneItemsIntoTile(d, chestTe);
            ctrl.targetDepositChestPos = null;
            world.func_72908_a(chestTe.field_145851_c + 0.5, chestTe.field_145848_d + 0.5, chestTe.field_145849_e + 0.5, "random.chestclosed", 0.5f, 1.0f);
            this.logger.log("MINE", ctrl, "Deposited items into chest",
                String.format("count=%d chest=(%d,%d,%d) remainingMineQueue=%d",
                    deposited, chestTe.field_145851_c, chestTe.field_145848_d, chestTe.field_145849_e, ctrl.mineQueue.size()));
            if (!ctrl.mineQueue.isEmpty() && !InventoryService.isInventoryFull(d.mainInventory())) {
                if (!ctrl.isScriptRunning() && owner != null) {
                    this.manager.droneSay(owner, ctrl, String.format("Deposited %d item(s) into chest! Resuming mining (%d blocks left).", deposited, ctrl.mineQueue.size()));
                }
                this.manager.setDroneMode(ctrl, Mode.MINE, "Resume mining after chest deposit");
            } else {
                if (!ctrl.isScriptRunning()) {
                    this.manager.droneSay(owner, ctrl, String.format("Deposited %d item(s) into chest at (%d, %d, %d).",
                        deposited, chestTe.field_145851_c, chestTe.field_145848_d, chestTe.field_145849_e));
                }
                this.manager.setDroneMode(ctrl, Mode.FOLLOW, "Finished chest deposit");
            }
        }
    }
}
