package ir.layercloud.cortexdrone.command;

import com.google.gson.JsonObject;
import ir.layercloud.cortexdrone.AIDroneManager;
import ir.layercloud.cortexdrone.CortexDroneMod;
import ir.layercloud.cortexdrone.behavior.MiningService;
import ir.layercloud.cortexdrone.config.AIConfig;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.inventory.InventoryService;
import ir.layercloud.cortexdrone.model.DebugEntry;
import ir.layercloud.cortexdrone.model.DroneController;
import ir.layercloud.cortexdrone.model.Mode;
import ir.layercloud.cortexdrone.world.ItemClassifier;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;

@RequiredArgsConstructor
public class DroneCommand extends CortexDroneMod.BaseCmd {
    private final String cmdName;
    private final AIDroneManager manager;

    @Override
    public String func_71517_b() {
        return this.cmdName;
    }

    @Override
    public String func_71518_a(ICommandSender sender) {
        return "/" + this.cmdName + " <spawn|link|despawn|list|status|follow|come|stay|goto|guard|cover|patrol|mine|farm|hunt|cook|smelt|sort|carry|setbase|scoop|plug|replace|take|give|torch|drop|chest|debug|config|prompt...>";
    }

    @Override
    public int func_82362_a() {
        return 0;
    }

    @Override
    public boolean func_71519_b(ICommandSender sender) {
        return true;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List func_71516_a(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return func_71530_a(args,
                "spawn", "link", "despawn", "list", "status", "follow", "come", "stay", "goto",
                "guard", "cover", "patrol", "mine", "farm", "hunt", "cook", "smelt", "sort", "carry", "setbase",
                "scoop", "plug", "replace", "take", "give", "torch", "drop", "chest", "scan", "js", "debug", "config", "help");
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("debug")) {
                return func_71530_a(args, "on", "off", "tail", "clear");
            }
            if (sub.equals("config")) {
                return func_71530_a(args, "url", "model", "key");
            }
            if (sub.equals("scoop")) {
                Set<String> fluidNames = net.minecraftforge.fluids.FluidRegistry.getRegisteredFluids().keySet();
                return func_71530_a(args, fluidNames.toArray(new String[0]));
            }
            if (sub.equals("replace")) {
                return func_71530_a(args, "floor", "walls", "ceiling", "north_wall", "south_wall", "west_wall", "east_wall", "all");
            }
            if (sub.equals("mine")) {
                Set<String> oreTokens = ir.layercloud.cortexdrone.world.RegistryIntrospector.getUndergroundResourceTokens();
                return func_71530_a(args, oreTokens.toArray(new String[0]));
            }
            if (sub.equals("farm")) {
                Set<String> cropTokens = ir.layercloud.cortexdrone.world.RegistryIntrospector.getCropAndPlantTokens();
                return func_71530_a(args, cropTokens.toArray(new String[0]));
            }
            if (sub.equals("cook") || sub.equals("smelt")) {
                return func_71530_a(args, "meat", "ores", "food", "all");
            }
            if (sub.equals("follow") || sub.equals("guard") || sub.equals("cover")) {
                MinecraftServer srv = MinecraftServer.func_71276_C();
                if (srv != null && srv.func_71213_z() != null) {
                    return func_71530_a(args, srv.func_71213_z());
                }
            }
        }
        return null;
    }

    @Override
    public void func_71515_b(ICommandSender sender, String[] args) {
        CortexDroneMod mod = this.manager.getMod();
        DroneLogger logger = this.manager.getLogger();
        AIConfig config = this.manager.getConfig();
        EntityPlayerMP player = (sender instanceof EntityPlayerMP) ? (EntityPlayerMP) sender : null;

        if (player != null && !mod.isAuthenticated(player)) {
            CortexDroneMod.sendMsg(player, EnumChatFormatting.RED + "[Auth] Please login first!");
            return;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== CortexDrone v1.3.0 (Modular Code Mode Engine) Commands ===");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone spawn [name] | link | despawn [name] | list | status");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone follow [player] | come | stay | goto <x> <y> <z> | carry [x y z] | setbase");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone guard [player] | cover [player] | patrol [radius] | hunt [mob] [count]");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone mine <target> | farm [crop] | scoop [lava|water] [count]");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone cook [item] | smelt [item] | sort [radius]");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone replace <floor|walls|ceiling|all> <block|held>");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone take <item> [count] | give [item] [count] | drop | chest");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "/drone torch | plug [radius] | debug [on|off|tail|clear]");
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.AQUA + "@drone <message>" + EnumChatFormatting.GRAY + " - Chat naturally with your drone!");
            return;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("debug")) {
            String mode = args.length >= 2 ? args[1].toLowerCase() : "toggle";
            if (mode.equals("tail")) {
                List<DebugEntry> tail = logger.getRecentDebugEntries(10);
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Drone Debug Log (last " + tail.size() + ") ===");
                for (DebugEntry e : tail) {
                    CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + e.toLogLine());
                }
                return;
            }
            if (mode.equals("clear")) {
                logger.clearDebugLog();
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.GREEN + "[DroneDebug] Cleared debug log buffer and file.");
                return;
            }
            if (player != null) {
                String pName = player.func_70005_c_();
                Set<String> subs = logger.getSubscribers();
                boolean enable = mode.equals("on") || (mode.equals("toggle") && !subs.contains(pName));
                if (enable) {
                    subs.add(pName);
                    CortexDroneMod.sendMsg(player, EnumChatFormatting.GREEN + "[DroneDebug] Live debug stream ENABLED for your chat. Use '/drone debug off' to stop.");
                } else {
                    subs.remove(pName);
                    CortexDroneMod.sendMsg(player, EnumChatFormatting.YELLOW + "[DroneDebug] Live debug stream DISABLED.");
                }
            } else {
                CortexDroneMod.sendMsg(sender, "Use /drone debug tail or /drone debug clear from console.");
            }
            return;
        }

        if (sub.equals("list")) {
            if (this.manager.getControllersById().isEmpty()) {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.YELLOW + "[Drone] No active AI drones.");
                return;
            }
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== Active AI Drones (" + this.manager.getControllersById().size() + ") ===");
            for (DroneController c : this.manager.getControllersById().values()) {
                if (c.drone == null || c.drone.field_70128_L) continue;
                CortexDroneMod.sendMsg(sender, String.format(
                    EnumChatFormatting.AQUA + "#%d %s" + EnumChatFormatting.GRAY + " (Owner: %s) "
                        + EnumChatFormatting.YELLOW + "[%s] " + EnumChatFormatting.WHITE + "@ (%.1f, %.1f, %.1f) "
                        + EnumChatFormatting.GREEN + "Items: %d",
                    c.id, c.name, c.ownerName, c.mode.name(),
                    c.drone.field_70165_t, c.drone.field_70163_u, c.drone.field_70161_v,
                    InventoryService.countInventoryItems(c.drone.mainInventory())
                ));
            }
            return;
        }

        if (sub.equals("config")) {
            boolean isOp = !(sender instanceof EntityPlayerMP) || sender.func_70003_b(2, "drone");
            if (!isOp) {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED + "[Drone] Only server operators can change AI config.");
                return;
            }
            if (args.length < 3) {
                String maskedKey = (config.getApiKey() != null && config.getApiKey().length() > 6)
                    ? (config.getApiKey().substring(0, 4) + "..." + config.getApiKey().substring(config.getApiKey().length() - 2))
                    : (config.getApiKey() == null || config.getApiKey().isEmpty() ? "<not set>" : "***");
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "[Drone AI Config] "
                    + EnumChatFormatting.YELLOW + "URL: " + EnumChatFormatting.WHITE + config.getApiUrl()
                    + EnumChatFormatting.YELLOW + " | Model: " + EnumChatFormatting.WHITE + config.getModel()
                    + EnumChatFormatting.YELLOW + " | Key: " + EnumChatFormatting.WHITE + maskedKey);
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.GRAY + "Usage: /drone config <url|model|key> <value>");
                return;
            }
            String key = args[1].toLowerCase();
            String val = args[2];
            if (key.equals("url")) {
                config.setApiUrl(val);
            } else if (key.equals("model")) {
                config.setModel(val);
            } else if (key.equals("key")) {
                config.setApiKey(val.equalsIgnoreCase("clear") ? "" : val);
            } else {
                CortexDroneMod.sendMsg(sender, EnumChatFormatting.RED + "Usage: /drone config <url|model|key> <value>");
                return;
            }
            this.manager.saveConfig();
            CortexDroneMod.sendMsg(sender, EnumChatFormatting.GREEN + "[Drone] Updated AI config '" + key + "' and saved to world/layercloud-ai.json.");
            return;
        }

        if (player == null) {
            CortexDroneMod.sendMsg(sender, "Use /drone list, /drone debug tail, or /drone config from console, or HTTP API on 127.0.0.1:25580.");
            return;
        }

        if (sub.equals("spawn")) {
            String customName = args.length >= 2 ? joinArgs(args, 1) : null;
            DroneController ctrl = this.manager.spawnDroneForPlayer(player, customName);
            this.manager.droneSay(player, ctrl, "Online and ready! Hovering nearby — look at me anytime to open my 8-slot inventory.");
            return;
        }

        if (sub.equals("link")) {
            DroneController ctrl = this.manager.linkNearestDrone(player);
            if (ctrl == null) {
                CortexDroneMod.sendMsg(player, EnumChatFormatting.RED + "[Drone] No OpenComputers Drone found within 8 blocks.");
            } else {
                this.manager.droneSay(player, ctrl, "Linked to AI Controller! Ready for commands.");
            }
            return;
        }

        DroneController ctrl = this.manager.getPlayerDrone(player.func_70005_c_());
        if (sub.equals("despawn") || sub.equals("remove")) {
            if (args.length >= 2) {
                String nameArg = args[1];
                for (DroneController c : this.manager.getControllersById().values()) {
                    if (c.name.equalsIgnoreCase(nameArg) || ("#" + c.id).equals(nameArg) || String.valueOf(c.id).equals(nameArg)) {
                        ctrl = c;
                        break;
                    }
                }
            }
            if (ctrl == null) {
                CortexDroneMod.sendMsg(player, EnumChatFormatting.RED + "[Drone] You don't have an active AI drone.");
                return;
            }
            String oldName = ctrl.name;
            int dropped = this.manager.despawnDrone(ctrl, player);
            CortexDroneMod.sendMsg(player, EnumChatFormatting.GREEN + "[Drone] Despawned '" + oldName + "' and dropped " + dropped + " item(s) at your feet.");
            return;
        }

        if (ctrl == null) {
            ctrl = this.manager.linkNearestDrone(player);
            if (ctrl == null) {
                ctrl = this.manager.spawnDroneForPlayer(player, null);
                this.manager.droneSay(player, ctrl, "Auto-spawned your AI Drone!");
            }
        }

        if (sub.equals("status") || sub.equals("info") || sub.equals("inv") || sub.equals("inventory")) {
            this.manager.sendDroneStatus(player, ctrl);
            return;
        }

        if (sub.equals("come") || sub.equals("here")) {
            this.manager.commandComeToPlayer(player, ctrl);
            return;
        }

        if (sub.equals("setbase")) {
            double bx = player.field_70165_t;
            double by = player.field_70163_u;
            double bz = player.field_70161_v;
            if (args.length >= 4) {
                try {
                    bx = parseCoord(bx, args[1]);
                    by = parseCoord(by, args[2]);
                    bz = parseCoord(bz, args[3]);
                } catch (NumberFormatException ignored) {
                }
            }
            config.setPlayerBase(player.func_70005_c_(), bx, by, bz);
            this.manager.saveConfig();
            this.manager.droneSay(player, ctrl, String.format("Saved your base location at (%.1f, %.1f, %.1f)!", bx, by, bz));
            return;
        }

        if (sub.equals("follow")) {
            String targetName = args.length >= 2 ? args[1] : player.func_70005_c_();
            ctrl.targetPlayerName = targetName;
            ctrl.clearAllTaskQueues();
            this.manager.setDroneMode(ctrl, Mode.FOLLOW, "Command /drone follow " + targetName);
            this.manager.saveConfig();
            this.manager.droneSay(player, ctrl, "Now following " + targetName + " (look at me within 4.5m to hold still for right-click!).");
            return;
        }

        if (sub.equals("stay") || sub.equals("stop") || sub.equals("hover")) {
            ctrl.cancelOtherLoops(0);
            ctrl.goalX = ctrl.drone.field_70165_t;
            ctrl.goalY = ctrl.drone.field_70163_u;
            ctrl.goalZ = ctrl.drone.field_70161_v;
            ctrl.clearAllTaskQueues();
            this.manager.setDroneMode(ctrl, Mode.STAY, "Command /drone stay");
            this.manager.saveConfig();
            this.manager.droneSay(player, ctrl, String.format("Hovering in place at (%.1f, %.1f, %.1f).", ctrl.goalX, ctrl.goalY, ctrl.goalZ));
            return;
        }

        if (sub.equals("goto") || sub.equals("move")) {
            if (args.length < 4) {
                CortexDroneMod.sendMsg(player, EnumChatFormatting.RED + "Usage: /drone goto <x> <y> <z>");
                return;
            }
            try {
                double gx = parseCoord(player.field_70165_t, args[1]);
                double gy = parseCoord(player.field_70163_u, args[2]);
                double gz = parseCoord(player.field_70161_v, args[3]);
                ctrl.goalX = gx;
                ctrl.goalY = gy;
                ctrl.goalZ = gz;
                ctrl.clearAllTaskQueues();
                this.manager.setDroneMode(ctrl, Mode.GOTO, String.format("Command /drone goto (%.1f,%.1f,%.1f)", gx, gy, gz));
                this.manager.droneSay(player, ctrl, String.format("Pathfinding to (%.1f, %.1f, %.1f)...", gx, gy, gz));
            } catch (NumberFormatException e) {
                CortexDroneMod.sendMsg(player, EnumChatFormatting.RED + "Invalid coordinates. Usage: /drone goto <x> <y> <z>");
            }
            return;
        }

        if (sub.equals("guard") || sub.equals("cover") || sub.equals("defend") || sub.equals("protect")) {
            String targetName = args.length >= 2 ? args[1] : player.func_70005_c_();
            ctrl.targetPlayerName = targetName;
            ctrl.guardFilter = "hostile|mutant";
            ctrl.clearAllTaskQueues();
            this.manager.setDroneMode(ctrl, Mode.GUARD, "Command /drone " + sub + " " + targetName);
            this.manager.saveConfig();
            this.manager.droneSay(player, ctrl, "Cover/Guard mode engaged for " + targetName + "! Will intercept reachable hostile & mutant mobs within 24m.");
            return;
        }

        if (sub.equals("patrol")) {
            int rad = (args.length >= 2 && MiningService.isNumericFilter(args[1])) ? Integer.parseInt(args[1]) : 36;
            ctrl.targetPlayerName = player.func_70005_c_();
            ctrl.patrolRadius = Math.max(16, rad);
            ctrl.guardFilter = "hostile|mutant";
            this.manager.setDroneMode(ctrl, Mode.PATROL, "Command /drone patrol " + ctrl.patrolRadius + "m");
            this.manager.droneSay(player, ctrl, "Patrolling " + ctrl.patrolRadius + "m around you and eliminating reachable hostile/mutant mobs!");
            return;
        }

        if (sub.equals("sort")) {
            int rad = (args.length >= 2 && MiningService.isNumericFilter(args[1])) ? Integer.parseInt(args[1]) : 28;
            JsonObject sr = this.manager.getChestSorter().sortNearbyChests(ctrl.drone.field_70170_p, ctrl, player, rad, "all");
            this.manager.droneSay(player, ctrl, "Sorted " + sr.get("chestsSorted").getAsInt() + " chest(s) and consolidated " + sr.get("stacksMerged").getAsInt() + " partial stack(s)!");
            return;
        }

        if (sub.equals("cook") || sub.equals("smelt")) {
            String filter = args.length >= 2 ? joinArgs(args, 1) : (sub.equals("cook") ? "meat" : "ores");
            int count = this.manager.getSmeltingService().startSmeltTask(ctrl, player, filter, 64);
            if (count > 0) {
                this.manager.droneSay(player, ctrl, "Cooking/smelting " + count + " '" + filter + "' item(s) in the nearest furnace and delivering to you!");
            } else {
                this.manager.droneSay(player, ctrl, "No smeltable '" + filter + "' items or furnace found within 36m.");
            }
            return;
        }

        if (sub.equals("carry") || sub.equals("airlift") || sub.equals("transport")) {
            double[] dest = this.manager.getWorldScanner().findBaseLocation(ctrl.drone.field_70170_p, player);
            if (args.length >= 4) {
                try {
                    dest[0] = parseCoord(player.field_70165_t, args[1]);
                    dest[1] = parseCoord(player.field_70163_u, args[2]);
                    dest[2] = parseCoord(player.field_70161_v, args[3]);
                } catch (NumberFormatException ignored) {
                }
            }
            this.manager.getPathfinder().startCarryPlayerTask(ctrl, player, dest[0], dest[1], dest[2]);
            this.manager.droneSay(player, ctrl, String.format("Airlifting you to (%.0f, %.0f, %.0f)!", dest[0], dest[1], dest[2]));
            return;
        }

        if (sub.equals("js") || sub.equals("eval") || sub.equals("code")) {
            final String code = args.length >= 2 ? joinArgs(args, 1) : "return await drone.getStatus();";
            final DroneController fCtrl = ctrl;
            final EntityPlayerMP fPlayer = player;
            CortexDroneMod.HTTP_POOL.submit(new Runnable() {
                @Override
                public void run() {
                    final JsonObject res = manager.getCodeModeRuntime().executeJavaScript(fCtrl, fPlayer, code);
                    manager.getMod().queueMain(new Runnable() {
                        @Override
                        public void run() {
                            manager.droneSay(fPlayer, fCtrl, "JS Result: " + res.toString());
                        }
                    });
                }
            });
            return;
        }

        if (sub.equals("plug")) {
            int r = args.length >= 2 && MiningService.isNumericFilter(args[1]) ? Integer.parseInt(args[1]) : 8;
            int cx = MathHelper.func_76128_c(player.field_70165_t);
            int cy = MathHelper.func_76128_c(player.field_70163_u);
            int cz = MathHelper.func_76128_c(player.field_70161_v);
            int count = this.manager.getBuildingService().plugLiquidsInBox(ctrl, cx - r, cy - r, cz - r, cx + r, cy + r, cz + r, "any");
            this.manager.droneSay(player, ctrl, "Queued " + count + " liquid block(s) within " + r + "m to plug!");
            return;
        }

        if (sub.equals("scoop") || sub.equals("bucket")) {
            String type = args.length >= 2 ? args[1] : "lava";
            int count = (args.length >= 3 && MiningService.isNumericFilter(args[2])) ? Integer.parseInt(args[2]) : 1;
            int sources = this.manager.getInventoryService().startScoopLiquidTask(ctrl, player, type, count);
            if (sources > 0) {
                this.manager.droneSay(player, ctrl, "Found " + sources + " source '" + type + "' block(s)! Scooping " + count + " bucket(s) and delivering to you.");
            } else {
                this.manager.droneSay(player, ctrl, "No source '" + type + "' blocks found within 36m.");
            }
            return;
        }

        if (sub.equals("replace")) {
            String surface = args.length >= 2 ? args[1] : "floor";
            String blockFilter = args.length >= 3 ? joinArgs(args, 2) : "held";
            int queued = this.manager.getBuildingService().startReplaceRoomTask(ctrl, player, surface, blockFilter, null, -1);
            this.manager.droneSay(player, ctrl, "Queued " + queued + " room '" + surface + "' block(s) to replace with '" + ItemClassifier.resolveHeldFilter(player, blockFilter) + "'!");
            return;
        }

        if (sub.equals("take")) {
            String filter = args.length >= 2 ? joinArgs(args, 1) : "held";
            int taken = this.manager.getInventoryService().takeFromPlayerInventory(ctrl, player, ItemClassifier.resolveHeldFilter(player, filter), 512);
            this.manager.droneSay(player, ctrl, "Took " + taken + " item(s) matching '" + ItemClassifier.resolveHeldFilter(player, filter) + "' from your inventory.");
            return;
        }

        if (sub.equals("give")) {
            String filter = args.length >= 2 ? joinArgs(args, 1) : "all";
            int given = this.manager.getInventoryService().giveToPlayerInventory(ctrl, player, filter, 512);
            this.manager.droneSay(player, ctrl, "Gave " + given + " item(s) matching '" + filter + "' to your inventory.");
            return;
        }

        if (sub.equals("hunt") || sub.equals("kill")) {
            String filter = args.length >= 2 ? args[1] : "sheep|cow|pig";
            int count = (args.length >= 3 && MiningService.isNumericFilter(args[2])) ? Integer.parseInt(args[2]) : 10;
            int nearby = this.manager.getCombatService().startHuntTask(ctrl, player, filter, count);
            this.manager.droneSay(player, ctrl, "Hunting up to " + count + " '" + filter + "' (" + nearby + " found within 80m)!");
            return;
        }

        if (sub.equals("farm") || sub.equals("replant")) {
            String target = args.length >= 2 ? joinArgs(args, 1) : "sugarcane";
            int count = this.manager.getMiningService().startFarmingTask(ctrl, player, target, 48);
            if (count > 0) {
                this.manager.droneSay(player, ctrl, "Found " + count + " '" + target + "' block(s)! Harvesting & auto-replanting now.");
            } else {
                this.manager.droneSay(player, ctrl, "No '" + target + "' blocks found within 48 blocks.");
            }
            return;
        }

        if (sub.equals("mine") || sub.equals("dig") || sub.equals("harvest")) {
            String target = args.length >= 2 ? joinArgs(args, 1) : "ores";
            int count = this.manager.getMiningService().startMiningTask(ctrl, player, target, 0, false);
            if (count > 0 && !ctrl.returnToOwnerAfterMine) {
                this.manager.droneSay(player, ctrl, "Found " + count + " '" + target + "' block(s)! Pathfinding & mining now.");
            } else if (count == 0) {
                this.manager.droneSay(player, ctrl, "No matching '" + target + "' blocks found within 1,200 blocks.");
            }
            return;
        }

        if (sub.equals("torch") || sub.equals("light")) {
            boolean placed = this.manager.getBuildingService().placeTorchNow(ctrl, player);
            ctrl.autoTorch = !ctrl.autoTorch;
            this.manager.saveConfig();
            this.manager.droneSay(player, ctrl, "Auto-torch mode " + (ctrl.autoTorch ? "ENABLED" : "DISABLED")
                + (placed ? " (and placed a torch!)." : "."));
            return;
        }

        if (sub.equals("drop")) {
            this.manager.setDroneMode(ctrl, Mode.DROP, "Command /drone drop");
            this.manager.droneSay(player, ctrl, "Flying to you to deliver all inventory items!");
            return;
        }

        if (sub.equals("chest") || sub.equals("deposit") || sub.equals("store")) {
            this.manager.setDroneMode(ctrl, Mode.DEPOSIT, "Command /drone chest");
            this.manager.droneSay(player, ctrl, "Flying to nearest chest within 24 blocks to deposit items...");
            return;
        }

        if (sub.equals("scan")) {
            double refX = player != null ? player.field_70165_t : ctrl.drone.field_70165_t;
            double refY = player != null ? player.field_70163_u : ctrl.drone.field_70163_u;
            double refZ = player != null ? player.field_70161_v : ctrl.drone.field_70161_v;
            java.util.Map<String, int[]> scan = this.manager.getWorldScanner().scanNearbyResources(ctrl.drone.field_70170_p, refX, refY, refZ, 24);
            if (scan.isEmpty()) {
                this.manager.droneSay(player, ctrl, "Scanned 24m radius: no ores, logs, or chests detected.");
            } else {
                StringBuilder sb = new StringBuilder("Scan (24m): ");
                int shown = 0;
                for (java.util.Map.Entry<String, int[]> e : scan.entrySet()) {
                    if (shown > 0) sb.append(", ");
                    int[] v = e.getValue();
                    sb.append(e.getKey()).append(" x").append(v[0]).append(" @(").append(v[1]).append(",").append(v[2]).append(",").append(v[3]).append(")");
                    if (++shown >= 5) break;
                }
                this.manager.droneSay(player, ctrl, sb.toString());
            }
            return;
        }

        String fullPrompt = joinArgs(args, 0);
        this.manager.handleNaturalInstruction(player, fullPrompt);
    }

    private static double parseCoord(double base, String raw) {
        if (raw.startsWith("~")) {
            if (raw.length() == 1) return base;
            return base + Double.parseDouble(raw.substring(1));
        }
        return Double.parseDouble(raw);
    }

    private static String joinArgs(String[] args, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }
}
