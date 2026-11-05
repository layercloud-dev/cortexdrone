package ir.layercloud.cortexdrone.action.combat;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.action.Action;

import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.inventory.ToolInspector;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.navigation.FlightPathfinder;
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
public class CombatService implements Action {
    private final AIDroneManager manager;
    private final DroneLogger logger;
    private final FlightPathfinder pathfinder;

    @Override
    public String category() { return "combat"; }

    @Override
    public String[] operations() { return new String[]{"attack_entity", "hunt", "guard", "patrol"}; }

    @Override
    public boolean supports(String operation) {
        return "attack_entity".equals(operation) || "hunt".equals(operation)
            || "guard".equals(operation) || "patrol".equals(operation);
    }

    @Override
    public JsonObject handle(DroneController ctrl, EntityPlayerMP player, String operation, JsonObject args) {
        JsonObject res = new JsonObject();
        if (args == null) args = new JsonObject();
        if ("attack_entity".equals(operation)) {
            int id = args.has("id") ? args.get("id").getAsInt() : -1;
            String filter = args.has("filter") ? args.get("filter").getAsString() : "hostile";
            res.addProperty("ok", startAttackEntityTask(ctrl, player, id, filter));
            res.addProperty("targetId", ctrl.targetAttackEntityId);
            return res;
        }
        if ("hunt".equals(operation)) {
            String filter = args.has("filter") ? args.get("filter").getAsString() : "sheep|cow|pig";
            int count = args.has("count") ? args.get("count").getAsInt() : 10;
            res.addProperty("ok", true);
            res.addProperty("nearbyMatching", startHuntTask(ctrl, player, filter, count));
            return res;
        }
        if ("guard".equals(operation)) {
            ctrl.targetPlayerName = args.has("target") ? args.get("target").getAsString() : (player != null ? player.func_70005_c_() : ctrl.ownerName);
            ctrl.guardFilter = args.has("filter") ? args.get("filter").getAsString() : "hostile|mutant";
            this.manager.setDroneMode(ctrl, Mode.GUARD, "Combat guard");
            res.addProperty("ok", true); return res;
        }
        if ("patrol".equals(operation)) {
            ctrl.targetPlayerName = args.has("target") ? args.get("target").getAsString() : (player != null ? player.func_70005_c_() : ctrl.ownerName);
            ctrl.patrolRadius = args.has("radius") ? Math.max(12, Math.min(80, args.get("radius").getAsInt())) : 36;
            ctrl.guardFilter = args.has("filter") ? args.get("filter").getAsString() : "hostile|mutant";
            this.manager.setDroneMode(ctrl, Mode.PATROL, "Combat patrol");
            res.addProperty("ok", true); return res;
        }
        res.addProperty("ok", false); res.addProperty("error", "Unsupported combat action: " + operation); return res;
    }

    public EntityLivingBase findEntityById(World world, int entityId) {
        if (world == null || entityId <= 0) return null;
        List<?> entities = world.field_72996_f;
        if (entities != null) {
            for (Object o : entities) {
                if (o instanceof EntityLivingBase) {
                    EntityLivingBase el = (EntityLivingBase) o;
                    if (el.func_145782_y() == entityId && !el.field_70128_L && el.func_110143_aJ() > 0.0f) {
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

        List<?> entities = world.field_72996_f;
        if (entities != null) {
            for (Object o : entities) {
                if (o instanceof EntityLivingBase) {
                    EntityLivingBase el = (EntityLivingBase) o;
                    if (!ItemClassifier.matchesEntityFilter(el, filter)) continue;
                    if (ctrl != null) {
                        Long blockedAt = ctrl.unreachableEntities.get(el.func_145782_y());
                        if (blockedAt != null && (now - blockedAt.longValue()) < 60000L) {
                            continue;
                        }
                    }
                    double dSq = el.func_70092_e(x, y, z);
                    if (dSq > maxDistSq) continue;
                    double dist = Math.sqrt(dSq);
                    double dy = Math.abs(el.field_70163_u - y);
                    boolean los = dist <= 28.0 && FlightPathfinder.isFlightLineClear(world, x, y + 0.5, z, el.field_70165_t, el.field_70163_u + 0.6, el.field_70161_v);
                    int ebx = MathHelper.func_76128_c(el.field_70165_t);
                    int ebz = MathHelper.func_76128_c(el.field_70161_v);
                    int topY = world.func_72976_f(ebx, ebz);
                    if (el.field_70163_u < topY - 3 && !los) {
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
        AxisAlignedBB box = AxisAlignedBB.func_72330_a(
            x - radius, Math.max(1.0, y - 8.0), z - radius,
            x + radius, Math.min(254.0, y + 10.0), z + radius
        );
        List<?> entities = world.func_72872_a(EntityLivingBase.class, box);
        EntityLivingBase best = null;
        double bestScore = Double.MAX_VALUE;
        String filter = (ctrl != null && ctrl.guardFilter != null && !ctrl.guardFilter.isEmpty()) ? ctrl.guardFilter : "hostile|mutant";
        if (entities != null) {
            for (Object o : entities) {
                if (o instanceof EntityLivingBase) {
                    EntityLivingBase el = (EntityLivingBase) o;
                    if (el.field_70128_L || el.func_110143_aJ() <= 0.0f) continue;
                    if (!ItemClassifier.isHostileOrMutantEntity(el) && !ItemClassifier.matchesEntityFilter(el, filter)) continue;
                    double dSq = el.func_70092_e(x, y, z);
                    if (dSq > radius * radius) continue;
                    double dist = Math.sqrt(dSq);
                    double dy = Math.abs(el.field_70163_u - y);
                    boolean playerSees = guarded != null && guarded.func_70685_l(el);
                    boolean droneSees = ctrl != null && ctrl.drone != null
                        && FlightPathfinder.isFlightLineClear(world, ctrl.drone.field_70165_t, ctrl.drone.field_70163_u, ctrl.drone.field_70161_v, el.field_70165_t, el.field_70163_u + 0.7, el.field_70161_v);
                    boolean clearLine = playerSees || droneSees
                        || FlightPathfinder.isFlightLineClear(world, x, y + 1.2, z, el.field_70165_t, el.field_70163_u + 0.7, el.field_70161_v);
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

    public boolean startAttackEntityTask(DroneController ctrl, EntityPlayerMP player, int entityId, String filter) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) return false;
        Drone d = ctrl.drone;
        World world = d.field_70170_p;
        EntityLivingBase target = entityId > 0
            ? findEntityById(world, entityId)
            : findNearestMatchingEntity(world, ctrl, d.field_70165_t, d.field_70163_u, d.field_70161_v, 256.0, filter != null ? filter : "hostile");
        if (target == null && player != null && player.field_70170_p == world && entityId <= 0) {
            target = findNearestMatchingEntity(world, ctrl, player.field_70165_t, player.field_70163_u, player.field_70161_v, 256.0, filter != null ? filter : "hostile");
        }
        if (target == null) return false;
        ctrl.targetAttackEntityId = target.func_145782_y();
        ctrl.huntFilter = target.func_70005_c_();
        ctrl.huntTargetCount = 1;
        ctrl.huntedCount = 0;
        this.manager.setDroneMode(ctrl, Mode.HUNT, "Attacking entity #" + ctrl.targetAttackEntityId + " (" + ctrl.huntFilter + ")");
        d.lightColor_$eq(0xFF2222);
        d.statusText_$eq("Attacking\n" + AIDroneManager.trimStatus(ctrl.huntFilter));
        return true;
    }

    public int startHuntTask(DroneController ctrl, EntityPlayerMP player, String filter, int maxCount) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) return 0;
        Drone d = ctrl.drone;
        World world = d.field_70170_p;
        ctrl.targetAttackEntityId = -1;
        ctrl.huntFilter = (filter == null || filter.trim().isEmpty()) ? "sheep|cow|pig|chicken" : filter.trim();
        ctrl.huntedCount = 0;

        double cx = player != null && player.field_70170_p == world ? player.field_70165_t : d.field_70165_t;
        double cy = player != null && player.field_70170_p == world ? player.field_70163_u : d.field_70163_u;
        double cz = player != null && player.field_70170_p == world ? player.field_70161_v : d.field_70161_v;

        int matching = 0;
        double nearestDist = Double.MAX_VALUE;
        List<?> list = world.field_72996_f;
        if (list != null) {
            for (Object o : list) {
                if (o instanceof EntityLivingBase) {
                    EntityLivingBase el = (EntityLivingBase) o;
                    if (ItemClassifier.matchesEntityFilter(el, ctrl.huntFilter)) {
                        double dist = el.func_70011_f(cx, cy, cz);
                        if (dist <= 1000.0) {
                            matching++;
                            if (dist < nearestDist) nearestDist = dist;
                        }
                    }
                }
            }
        }

        ctrl.huntTargetCount = Math.max(1, Math.min(64, maxCount));

        if (matching > 0) {
            this.manager.setDroneMode(ctrl, Mode.HUNT, "Hunting '" + ctrl.huntFilter + "' (target=" + ctrl.huntTargetCount + ", nearby=" + matching + ", nearest=" + Math.round(nearestDist) + "m)");
            d.lightColor_$eq(0xFF2222);
            d.statusText_$eq("Hunting 0/" + ctrl.huntTargetCount + "\n" + AIDroneManager.trimStatus(ctrl.huntFilter));
        } else {
            this.logger.log("HUNT", ctrl, "No entities matching '" + ctrl.huntFilter + "' within 1000m", "");
        }
        return matching;
    }

    private static int countRawMeatInInventory(IInventory inv) {
        if (inv == null) return 0;
        int total = 0;
        for (int i = 0; i < inv.func_70302_i_(); i++) {
            ItemStack st = inv.func_70301_a(i);
            if (st != null && st.field_77994_a > 0 && st.func_77973_b() instanceof ItemFood) {
                total += st.field_77994_a;
            }
        }
        return total;
    }

    private static void ensureMeatPickupSpace(IInventory inv) {
        if (inv == null) return;
        for (int i = 0; i < inv.func_70302_i_(); i++) {
            if (inv.func_70301_a(i) == null) return;
        }
        // Dynamically evict 1 low-priority non-food/non-tool/non-ore stack (category >= 4: building/misc byproduct)
        for (int i = inv.func_70302_i_() - 1; i >= 0; i--) {
            ItemStack st = inv.func_70301_a(i);
            if (st != null && !(st.func_77973_b() instanceof ItemFood) && ItemClassifier.getItemCategoryOrder(st) >= 4) {
                inv.func_70299_a(i, null);
                inv.func_70296_d();
                return;
            }
        }
    }

    private static float resolveEffectiveAttackDamage(IInventory inv, EntityLivingBase target, float baseDamage) {
        return ToolInspector.computeAttackDamage(inv, target, baseDamage);
    }

    public void tickHuntBehavior(DroneController ctrl, EntityPlayerMP owner) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) return;
        Drone d = ctrl.drone;
        World world = d.field_70170_p;
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
            targetMob = findNearestMatchingEntity(world, ctrl, d.field_70165_t, d.field_70163_u, d.field_70161_v, 1000.0, ctrl.huntFilter);
            if (targetMob == null && owner != null && owner.field_70170_p == world) {
                targetMob = findNearestMatchingEntity(world, ctrl, owner.field_70165_t, owner.field_70163_u, owner.field_70161_v, 1000.0, ctrl.huntFilter);
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

        double mx = targetMob.field_70165_t;
        double my = targetMob.field_70163_u + Math.max(0.5, targetMob.field_70131_O * 0.65);
        double mz = targetMob.field_70161_v;
        this.pathfinder.navigateToward(ctrl, mx, my, mz, 0.14f);
        double distMob = d.func_70011_f(mx, my, mz);
        double horizMob = Math.hypot(mx - d.field_70165_t, mz - d.field_70161_v);
        d.statusText_$eq("Hunt " + ctrl.huntedCount + "/" + ctrl.huntTargetCount + "\n" + (int) distMob + "m");

        if (distMob > 4.2 && (ctrl.stuckTicks >= 12 || (ctrl.lastPathPartial && horizMob <= 5.0))) {
            ctrl.unreachableEntities.put(targetMob.func_145782_y(), System.currentTimeMillis());
            ctrl.stuckTicks = 0;
            ctrl.pathWaypoints.clear();
            this.logger.log("HUNT", ctrl, "Skipping unreachable/buried entity " + targetMob.func_70005_c_(),
                String.format("pos=(%.1f,%.1f,%.1f)", mx, my, mz));
            return;
        }

        if (distMob <= 4.2 && ctrl.attackCooldown <= 0) {
            ctrl.attackCooldown = 4;
            DamageSource ds = (owner != null) ? DamageSource.func_76365_a(owner) : DamageSource.field_76377_j;
            int fireSec = ToolInspector.getFireAspectSeconds(d.mainInventory());
            if (fireSec > 0) targetMob.func_70015_d(fireSec);
            targetMob.func_70097_a(ds, resolveEffectiveAttackDamage(d.mainInventory(), targetMob, 20.0f));
            world.func_72956_a(d, "mob.irongolem.hit", 0.6f, 1.6f);
            if (world instanceof WorldServer) {
                ((WorldServer) world).func_147487_a("crit", mx, my, mz, 6, 0.25, 0.25, 0.25, 0.1);
            }
            if (targetMob.func_110143_aJ() <= 0.0f || targetMob.field_70128_L) {
                ctrl.huntedCount++;
                ensureMeatPickupSpace(d.mainInventory());
                InventoryService.suckNearbyItems(d, mx, targetMob.field_70163_u, mz, 6.5);
                this.logger.log("HUNT", ctrl, "Killed " + targetMob.func_70005_c_(),
                    "progress=" + ctrl.huntedCount + "/" + ctrl.huntTargetCount);
            }
        }
    }

    public void tickGuardOrPatrolBehavior(DroneController ctrl) {
        if (ctrl == null || ctrl.drone == null || ctrl.drone.field_70170_p == null) return;
        Drone d = ctrl.drone;
        World world = d.field_70170_p;
        EntityPlayerMP guarded = CortexDroneMod.findPlayer(
            ctrl.targetPlayerName != null ? ctrl.targetPlayerName : ctrl.ownerName
        );
        double cx = (guarded != null && guarded.field_70170_p == world) ? guarded.field_70165_t : d.field_70165_t;
        double cy = (guarded != null && guarded.field_70170_p == world) ? guarded.field_70163_u : d.field_70163_u;
        double cz = (guarded != null && guarded.field_70170_p == world) ? guarded.field_70161_v : d.field_70161_v;

        double scanRange = ctrl.mode == Mode.PATROL ? Math.max(24.0, (double) ctrl.patrolRadius) : 24.0;
        EntityLivingBase hostile = findNearestReachableHostileMob(world, ctrl, guarded, cx, cy, cz, scanRange);
        if (hostile != null) {
            double mx = hostile.field_70165_t;
            double my = hostile.field_70163_u + Math.max(0.6, hostile.field_70131_O * 0.65);
            double mz = hostile.field_70161_v;
            this.pathfinder.navigateToward(ctrl, mx, my, mz, 0.12f);
            d.lightColor_$eq(0xFF2222);
            d.statusText_$eq("Attacking\n" + AIDroneManager.trimStatus(hostile.func_70005_c_()));

            double distMob = d.func_70011_f(mx, my, mz);
            if (distMob > 2.0 && FlightPathfinder.isFlightLineClear(world, d.field_70165_t, d.field_70163_u, d.field_70161_v, mx, my, mz)) {
                d.field_70159_w += (mx - d.field_70165_t) / distMob * 0.08;
                d.field_70181_x += (my - d.field_70163_u) / distMob * 0.08;
                d.field_70179_y += (mz - d.field_70161_v) / distMob * 0.08;
            }
            if (distMob <= 3.5 && ctrl.attackCooldown <= 0) {
                ctrl.attackCooldown = 6;
                DamageSource ds = (guarded != null) ? DamageSource.func_76365_a(guarded) : DamageSource.field_76377_j;
                int fireSec = ToolInspector.getFireAspectSeconds(d.mainInventory());
                if (fireSec > 0) hostile.func_70015_d(fireSec);
                hostile.func_70097_a(ds, resolveEffectiveAttackDamage(d.mainInventory(), hostile, 10.0f));
                world.func_72956_a(d, "mob.irongolem.hit", 0.6f, 1.6f);
                if (world instanceof WorldServer) {
                    ((WorldServer) world).func_147487_a("crit", mx, my, mz, 6, 0.25, 0.25, 0.25, 0.1);
                }
                if (hostile.func_110143_aJ() <= 0.0f) {
                    ctrl.huntedCount++;
                    InventoryService.suckNearbyItems(d, mx, hostile.field_70163_u, mz, 5.0);
                }
            }
        } else {
            if (guarded != null && FlightPathfinder.isPlayerLookingAtDrone(guarded, d)) {
                ctrl.playerLookHold = true;
                FlightPathfinder.setDroneFlightTarget(d, d.field_70165_t, d.field_70163_u, d.field_70161_v, 0.1f);
                d.field_70159_w *= 0.25;
                d.field_70181_x *= 0.25;
                d.field_70179_y *= 0.25;
                d.statusText_$eq("Ready\n[Right-Click]");
                return;
            }
            ctrl.playerLookHold = false;
            if (guarded == null || guarded.field_70170_p != world) {
                FlightPathfinder.setDroneFlightTarget(d, d.field_70165_t, d.field_70163_u, d.field_70161_v, 0.05f);
                ctrl.stuckTicks = 0;
                return;
            }
            double orbitR = ctrl.mode == Mode.PATROL ? 5.5 : 2.5;
            double ox = cx + Math.cos(ctrl.orbitAngle * 0.9f) * orbitR;
            double oy = cy + (ctrl.mode == Mode.PATROL ? 2.4 : 1.6);
            double oz = cz + Math.sin(ctrl.orbitAngle * 0.9f) * orbitR;
            if (ItemClassifier.isPassableVoxel(world, MathHelper.func_76128_c(ox), MathHelper.func_76128_c(oy), MathHelper.func_76128_c(oz))) {
                this.pathfinder.navigateToward(ctrl, ox, oy, oz, 0.1f);
            }
            d.lightColor_$eq(ctrl.mode == Mode.PATROL ? 0xFF6600 : 0xFFAA00);
            d.statusText_$eq((ctrl.mode == Mode.PATROL ? "Patrolling\n" : "Guarding\n")
                + AIDroneManager.trimStatus(guarded.func_70005_c_()));
        }
    }
}
