package ir.layercloud.cortexdrone.action.combat;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.inventory.ToolInspector;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.service.FlightPathfinder;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.util.List;
import li.cil.oc.common.entity.Drone;
import lombok.RequiredArgsConstructor;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

@RequiredArgsConstructor
public class CombatService {
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final FlightPathfinder pathfinder;

    public EntityLivingBase findEntityById(World world, int entityId) {
        if (world == null || entityId <= 0) return null;
        List<?> entities = world.loadedEntityList;
        if (entities != null) {
            for (Object o : entities) {
                if (o instanceof EntityLivingBase) {
                    EntityLivingBase el = (EntityLivingBase) o;
                    if (el.getEntityId() == entityId && !el.isDead && el.getHealth() > 0.0f) {
                        return el;
                    }
                }
            }
        }
        return null;
    }

    public EntityLivingBase findNearestMatchingEntity(World world, DroneController ctrl, double x, double y, double z, double radius, String filter) {
        if (world == null || radius <= 0.0) return null;
        double maxDistSq = radius * radius;
        EntityLivingBase best = null;
        double bestScore = Double.MAX_VALUE;
        long now = System.currentTimeMillis();

        List<?> entities = world.loadedEntityList;
        if (entities != null) {
            for (Object o : entities) {
                if (o instanceof EntityLivingBase) {
                    EntityLivingBase el = (EntityLivingBase) o;
                    if (!ItemClassifier.matchesEntityFilter(el, filter)) continue;
                    if (ctrl != null) {
                        Long blockedAt = ctrl.unreachableEntities.get(el.getEntityId());
                        if (blockedAt != null && (now - blockedAt.longValue()) < 60000L) {
                            continue;
                        }
                    }
                    double dSq = el.getDistanceSq(x, y, z);
                    if (dSq > maxDistSq) continue;
                    double dist = Math.sqrt(dSq);
                    double dy = Math.abs(el.posY - y);
                    boolean los = dist <= 28.0 && FlightPathfinder.isFlightLineClear(world, x, y + 0.5, z, el.posX, el.posY + 0.6, el.posZ);
                    int ebx = MathHelper.floor_double(el.posX);
                    int ebz = MathHelper.floor_double(el.posZ);
                    int topY = world.getHeightValue(ebx, ebz);
                    if (el.posY < topY - 3 && !los) {
                        continue;
                    }
                    double score = dist + (dy > 12.0 && !los ? dy * 2.0 : 0.0) - (los ? 15.0 : 0.0);
                    if (score < bestScore) {
                        bestScore = score;
                        best = el;
                    }
                }
            }
        }
        return best;
    }

    public EntityLivingBase findNearestReachableHostileMob(World world, DroneController ctrl, EntityPlayerMP guarded, double x, double y, double z, double radius) {
        if (world == null || radius <= 0.0) return null;
        AxisAlignedBB box = AxisAlignedBB.getBoundingBox(
            x - radius, Math.max(1.0, y - 8.0), z - radius,
            x + radius, Math.min(254.0, y + 10.0), z + radius
        );
        List<?> entities = world.getEntitiesWithinAABB(EntityLivingBase.class, box);
        EntityLivingBase best = null;
        double bestScore = Double.MAX_VALUE;
        String filter = (ctrl != null && ctrl.guardFilter != null && !ctrl.guardFilter.isEmpty()) ? ctrl.guardFilter : "hostile|mutant";
        if (entities != null) {
            for (Object o : entities) {
                if (o instanceof EntityLivingBase) {
                    EntityLivingBase el = (EntityLivingBase) o;
                    if (el.isDead || el.getHealth() <= 0.0f) continue;
                    if (!ItemClassifier.isHostileOrMutantEntity(el) && !ItemClassifier.matchesEntityFilter(el, filter)) continue;
                    double dSq = el.getDistanceSq(x, y, z);
                    if (dSq > radius * radius) continue;
                    double dist = Math.sqrt(dSq);
                    double dy = Math.abs(el.posY - y);
                    boolean playerSees = guarded != null && guarded.canEntityBeSeen(el);
                    boolean droneSees = ctrl != null && ctrl.drone != null
                        && FlightPathfinder.isFlightLineClear(world, ctrl.drone.posX, ctrl.drone.posY, ctrl.drone.posZ, el.posX, el.posY + 0.7, el.posZ);
                    boolean clearLine = playerSees || droneSees
                        || FlightPathfinder.isFlightLineClear(world, x, y + 1.2, z, el.posX, el.posY + 0.7, el.posZ);
                    if (!clearLine && dy > 4.5) continue;
                    double score = dist - (clearLine ? 12.0 : 0.0);
                    if (score < bestScore) {
                        bestScore = score;
                        best = el;
                    }
                }
            }
        }
        return best;
    }

    private static int countRawMeatInInventory(IInventory inv) {
        if (inv == null) return 0;
        int total = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st != null && st.stackSize > 0 && st.getItem() instanceof ItemFood) {
                total += st.stackSize;
            }
        }
        return total;
    }

    private static void ensureMeatPickupSpace(IInventory inv) {
        if (inv == null) return;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            if (inv.getStackInSlot(i) == null) return;
        }
        // Dynamically evict 1 low-priority non-food/non-tool/non-ore stack (category >= 4: building/misc byproduct)
        for (int i = inv.getSizeInventory() - 1; i >= 0; i--) {
            ItemStack st = inv.getStackInSlot(i);
            if (st != null && !(st.getItem() instanceof ItemFood) && ItemClassifier.getItemCategoryOrder(st) >= 4) {
                inv.setInventorySlotContents(i, null);
                inv.markDirty();
                return;
            }
        }
    }

    private static float resolveEffectiveAttackDamage(IInventory inv, EntityLivingBase target, float baseDamage) {
        return ToolInspector.computeAttackDamage(inv, target, baseDamage);
    }

    public void tickHuntBehavior(DroneController ctrl, EntityPlayerMP owner) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.worldObj == null) return;
        Drone d = ctrl.drone;
        World world = d.worldObj;
        d.lightColor_$eq(0xFF2222);

        int meatCount = countRawMeatInInventory(d.mainInventory());
        if (InventoryService.isInventoryFull(d.mainInventory())
            || ctrl.huntedCount >= ctrl.huntTargetCount
            || (ctrl.huntTargetCount >= 20 && meatCount >= 64)) {
            ctrl.targetAttackEntityId = -1;
            if (!ctrl.isScriptRunning() && owner != null) {
                this.manager.droneSay(owner, ctrl, "Finished hunting (" + ctrl.huntedCount + " killed)! Flying back to deliver drops.");
            }
            this.logger.log("HUNT", ctrl, "Hunt target reached", "hunted=" + ctrl.huntedCount + " meat=" + meatCount + " scriptRunning=" + ctrl.isScriptRunning());
            Mode nextMode = ctrl.isScriptRunning() ? Mode.FOLLOW : (ctrl.huntedCount > 0 ? Mode.DROP : Mode.FOLLOW);
            this.manager.setDroneMode(ctrl, nextMode, "Hunt completed");
            return;
        }

        EntityLivingBase targetMob = null;
        if (ctrl.targetAttackEntityId > 0) {
            targetMob = findEntityById(world, ctrl.targetAttackEntityId);
        } else {
            targetMob = findNearestMatchingEntity(world, ctrl, d.posX, d.posY, d.posZ, 1000.0, ctrl.huntFilter);
            if (targetMob == null && owner != null && owner.worldObj == world) {
                targetMob = findNearestMatchingEntity(world, ctrl, owner.posX, owner.posY, owner.posZ, 1000.0, ctrl.huntFilter);
            }
        }

        if (targetMob == null) {
            ctrl.targetAttackEntityId = -1;
            if (!ctrl.isScriptRunning() && owner != null) {
                this.manager.droneSay(owner, ctrl, ctrl.huntedCount > 0
                    ? ("No more '" + ctrl.huntFilter + "' within 1000m (" + ctrl.huntedCount + " hunted)! Delivering drops to you.")
                    : ("No '" + ctrl.huntFilter + "' found within 1000m."));
            }
            Mode nextMode = ctrl.isScriptRunning() ? Mode.FOLLOW : (ctrl.huntedCount > 0 ? Mode.DROP : Mode.FOLLOW);
            this.manager.setDroneMode(ctrl, nextMode, "No more matching entities to hunt");
            return;
        }

        double mx = targetMob.posX;
        double my = targetMob.posY + Math.max(0.5, targetMob.height * 0.65);
        double mz = targetMob.posZ;
        this.pathfinder.navigateToward(ctrl, mx, my, mz, 0.14f);
        double distMob = d.getDistance(mx, my, mz);
        double horizMob = Math.hypot(mx - d.posX, mz - d.posZ);
        d.statusText_$eq("Hunt " + ctrl.huntedCount + "/" + ctrl.huntTargetCount + "\n" + (int) distMob + "m");

        if (distMob > 4.2 && (ctrl.stuckTicks >= 12 || (ctrl.lastPathPartial && horizMob <= 5.0))) {
            ctrl.unreachableEntities.put(targetMob.getEntityId(), System.currentTimeMillis());
            ctrl.stuckTicks = 0;
            ctrl.pathWaypoints.clear();
            this.logger.log("HUNT", ctrl, "Skipping unreachable/buried entity " + targetMob.getCommandSenderName(),
                String.format("pos=(%.1f,%.1f,%.1f)", mx, my, mz));
            return;
        }

        if (distMob <= 4.2 && ctrl.attackCooldown <= 0) {
            ctrl.attackCooldown = 4;
            DamageSource ds = (owner != null) ? DamageSource.causePlayerDamage(owner) : DamageSource.generic;
            int fireSec = ToolInspector.getFireAspectSeconds(d.mainInventory());
            if (fireSec > 0) targetMob.setFire(fireSec);
            targetMob.attackEntityFrom(ds, resolveEffectiveAttackDamage(d.mainInventory(), targetMob, 20.0f));
            world.playSoundAtEntity(d, "mob.irongolem.hit", 0.6f, 1.6f);
            if (world instanceof WorldServer) {
                ((WorldServer) world).func_147487_a("crit", mx, my, mz, 6, 0.25, 0.25, 0.25, 0.1);
            }
            if (targetMob.getHealth() <= 0.0f || targetMob.isDead) {
                ctrl.huntedCount++;
                ensureMeatPickupSpace(d.mainInventory());
                InventoryService.suckNearbyItems(d, mx, targetMob.posY, mz, 6.5);
                this.logger.log("HUNT", ctrl, "Killed " + targetMob.getCommandSenderName(),
                    "progress=" + ctrl.huntedCount + "/" + ctrl.huntTargetCount);
            }
        }
    }

    public void tickGuardOrPatrolBehavior(DroneController ctrl) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.worldObj == null) return;
        Drone d = ctrl.drone;
        World world = d.worldObj;
        EntityPlayerMP guarded = CortexDroneMod.findPlayer(
            ctrl.targetPlayerName != null ? ctrl.targetPlayerName : ctrl.ownerName
        );
        double cx = (guarded != null && guarded.worldObj == world) ? guarded.posX : d.posX;
        double cy = (guarded != null && guarded.worldObj == world) ? guarded.posY : d.posY;
        double cz = (guarded != null && guarded.worldObj == world) ? guarded.posZ : d.posZ;

        double scanRange = ctrl.mode == Mode.PATROL ? Math.max(24.0, (double) ctrl.patrolRadius) : 24.0;
        EntityLivingBase hostile = findNearestReachableHostileMob(world, ctrl, guarded, cx, cy, cz, scanRange);
        if (hostile != null) {
            double mx = hostile.posX;
            double my = hostile.posY + Math.max(0.6, hostile.height * 0.65);
            double mz = hostile.posZ;
            this.pathfinder.navigateToward(ctrl, mx, my, mz, 0.12f);
            d.lightColor_$eq(0xFF2222);
            d.statusText_$eq("Attacking\n" + AIDroneManager.trimStatus(hostile.getCommandSenderName()));

            double distMob = d.getDistance(mx, my, mz);
            if (distMob > 2.0 && FlightPathfinder.isFlightLineClear(world, d.posX, d.posY, d.posZ, mx, my, mz)) {
                d.motionX += (mx - d.posX) / distMob * 0.08;
                d.motionY += (my - d.posY) / distMob * 0.08;
                d.motionZ += (mz - d.posZ) / distMob * 0.08;
            }
            if (distMob <= 3.5 && ctrl.attackCooldown <= 0) {
                ctrl.attackCooldown = 6;
                DamageSource ds = (guarded != null) ? DamageSource.causePlayerDamage(guarded) : DamageSource.generic;
                int fireSec = ToolInspector.getFireAspectSeconds(d.mainInventory());
                if (fireSec > 0) hostile.setFire(fireSec);
                hostile.attackEntityFrom(ds, resolveEffectiveAttackDamage(d.mainInventory(), hostile, 10.0f));
                world.playSoundAtEntity(d, "mob.irongolem.hit", 0.6f, 1.6f);
                if (world instanceof WorldServer) {
                    ((WorldServer) world).func_147487_a("crit", mx, my, mz, 6, 0.25, 0.25, 0.25, 0.1);
                }
                if (hostile.getHealth() <= 0.0f) {
                    ctrl.huntedCount++;
                    InventoryService.suckNearbyItems(d, mx, hostile.posY, mz, 5.0);
                }
            }
        } else {
            if (guarded != null && FlightPathfinder.isPlayerLookingAtDrone(guarded, d)) {
                ctrl.playerLookHold = true;
                FlightPathfinder.setDroneFlightTarget(d, d.posX, d.posY, d.posZ, 0.1f);
                d.motionX *= 0.25;
                d.motionY *= 0.25;
                d.motionZ *= 0.25;
                d.statusText_$eq("Ready\n[Right-Click]");
                return;
            }
            ctrl.playerLookHold = false;
            if (guarded == null || guarded.worldObj != world) {
                FlightPathfinder.setDroneFlightTarget(d, d.posX, d.posY, d.posZ, 0.05f);
                ctrl.stuckTicks = 0;
                return;
            }
            double orbitR = ctrl.mode == Mode.PATROL ? 5.5 : 2.5;
            double ox = cx + Math.cos(ctrl.orbitAngle * 0.9f) * orbitR;
            double oy = cy + (ctrl.mode == Mode.PATROL ? 2.4 : 1.6);
            double oz = cz + Math.sin(ctrl.orbitAngle * 0.9f) * orbitR;
            if (ItemClassifier.isPassableVoxel(world, MathHelper.floor_double(ox), MathHelper.floor_double(oy), MathHelper.floor_double(oz))) {
                this.pathfinder.navigateToward(ctrl, ox, oy, oz, 0.1f);
            }
            d.lightColor_$eq(ctrl.mode == Mode.PATROL ? 0xFF6600 : 0xFFAA00);
            d.statusText_$eq((ctrl.mode == Mode.PATROL ? "Patrolling\n" : "Guarding\n")
                + AIDroneManager.trimStatus(guarded.getCommandSenderName()));
        }
    }
}
