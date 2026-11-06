package ir.layercloud.cortexdrone;

import cpw.mods.fml.common.event.FMLServerStartingEvent;
import ir.layercloud.cortexdrone.command.impl.DroneCommand;
import ir.layercloud.cortexdrone.config.AIConfig;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.harness.*;
import ir.layercloud.cortexdrone.harness.rpc.translate.ActionRpcTranslator;
import ir.layercloud.cortexdrone.http.DroneHttpServer;
import ir.layercloud.cortexdrone.manager.ActionRegistry;
import ir.layercloud.cortexdrone.manager.TickManager;
import ir.layercloud.cortexdrone.manager.building.impl.BuildingManager;
import ir.layercloud.cortexdrone.manager.interact.impl.InteractionManager;
import ir.layercloud.cortexdrone.manager.inventory.impl.InventoryManager;
import ir.layercloud.cortexdrone.manager.navigation.impl.NavigationManager;
import ir.layercloud.cortexdrone.manager.visuals.impl.VisualsManager;
import ir.layercloud.cortexdrone.drone.DroneController;
import ir.layercloud.cortexdrone.drone.Mode;
import ir.layercloud.cortexdrone.oc.OcHardwareBridge;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.navigation.impl.BreadcrumbService;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import ir.layercloud.cortexdrone.service.world.DocLookupService;
import ir.layercloud.cortexdrone.service.world.ItemClassifier;
import ir.layercloud.cortexdrone.service.world.WorldScanner;
import li.cil.oc.common.entity.Drone;
import lombok.Getter;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.ServerChatEvent;

import java.io.File;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Getter
public class AIDroneManager {
    private final CortexDroneMod mod;
    private File configFile;
    private final AIConfig config = new AIConfig();

    private final DroneLogger logger = new DroneLogger();
    private final FlightPathfinder pathfinder;
    private final BreadcrumbService breadcrumbService;
    private final NavigationManager navigationManager;
    private final WorldScanner worldScanner;
    private final InventoryService inventoryService;
    private final BuildingManager buildingManager;
    private final VisualsManager visualsManager;
    private final InteractionManager interactionManager;
    private final InventoryManager inventoryManager;
    private final ActionRegistry actionRegistry;
    private final ActionRpcTranslator actionRpcTranslator;
    private final DocLookupService docLookupService;
    private SkillLibrary skillLibrary;
    private final CodeModeRuntime codeModeRuntime;
    private final RpcDispatcher rpcDispatcher;
    private final LlmAgentService agentService;
    private final TickManager tickManager;
    private final TickProfiler tickProfiler;
    private final DroneHttpServer httpServer;
    private final TaskCoordinator taskCoordinator;

    private final AtomicInteger nextDroneId = new AtomicInteger(1);
    private final Map<Integer, DroneController> controllersById = new ConcurrentHashMap<>();
    private final Map<String, DroneController> controllersByOwner = new ConcurrentHashMap<>();
    private int tickCounter = 0;
    private final OcHardwareBridge ocHardwareBridge = new OcHardwareBridge();
    private final java.util.Set<UUID> despawnedUuids = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public AIDroneManager(CortexDroneMod mod) {
        this.mod = mod;
        this.pathfinder = new FlightPathfinder(this.logger);
        this.breadcrumbService = new BreadcrumbService();
        this.worldScanner = new WorldScanner(this.logger, this.config);
        this.inventoryService = new InventoryService(this.logger);
        this.visualsManager = new VisualsManager(this, this.logger);
        this.interactionManager = new InteractionManager(this.inventoryService);
        this.inventoryManager = new InventoryManager(this, this.inventoryService);
        this.buildingManager = new BuildingManager(this, this.logger, this.inventoryService);
        this.navigationManager = new NavigationManager(this, this.logger,
                this.pathfinder, this.breadcrumbService);
        this.actionRegistry = new ActionRegistry();
        this.actionRegistry.register(this.buildingManager);
        this.actionRegistry.register(this.interactionManager);
        this.actionRegistry.register(this.inventoryManager);
        this.actionRegistry.register(this.visualsManager);
        this.actionRpcTranslator = new ActionRpcTranslator(this);
        this.docLookupService = new DocLookupService(this.inventoryService);
        this.skillLibrary = new SkillLibrary(new File("./world"));
        this.codeModeRuntime = new CodeModeRuntime(this.logger, this.config);
        this.rpcDispatcher = new RpcDispatcher(this);
        this.agentService = new LlmAgentService(
                this,
                this.logger,
                this.worldScanner,
                this.codeModeRuntime,
                this.rpcDispatcher
        );
        this.tickManager = new TickManager(
                this,
                this.logger,
                this.inventoryService,
                this.buildingManager,
                this.visualsManager,
                this.navigationManager,
                this.breadcrumbService
        );
        this.tickProfiler = new TickProfiler(this.logger);
        this.httpServer = new DroneHttpServer(this, this.logger);
        this.taskCoordinator = new TaskCoordinator(this);
    }

    public void onServerStarting(FMLServerStartingEvent event, File worldDir) {
        this.configFile = new File(worldDir, "layercloud-ai.json");
        this.skillLibrary = new SkillLibrary(worldDir);
        this.logger.setDebugLogFile(new File(worldDir, "layercloud-drone-debug.log"));
        loadConfig();
        reconnectUnlinkedWorldDrones();
        this.logger.log("SYSTEM", null, "CortexDrone v" + CortexDroneMod.VERSION + " (Master-Tool Code Mode Harness) initialized", "worldDir=" + worldDir.getAbsolutePath());

        event.registerServerCommand(new DroneCommand("drone", this));

        this.httpServer.start();
    }

    public void onServerStopping() {
        this.logger.log("SYSTEM", null, "Server stopping; saving AI drone config and stopping HTTP server", "");
        for (DroneController ctrl : this.controllersById.values()) {
            if (ctrl != null && !ctrl.lightCells.isEmpty()) {
                this.visualsManager.clearLighting(ctrl);
            }
        }
        saveConfig();
        this.httpServer.stop();
    }

    public synchronized void loadConfig() {
        AIConfig loaded = AIConfig.load(this.configFile, CortexDroneMod.GSON);
        this.config.setApiUrl(loaded.getApiUrl());
        this.config.setModel(loaded.getModel());
        this.config.setApiKey(loaded.getApiKey());
        this.config.setHttpPort(loaded.getHttpPort());
        this.config.setSavedDrones(loaded.getSavedDrones());
        this.config.setPlayerBases(loaded.getPlayerBases());
        this.config.setSavedLocations(loaded.getSavedLocations());
    }

    public synchronized void saveConfig() {
        this.config.save(this.configFile, CortexDroneMod.GSON, this.controllersById.values());
    }

    public <T> T callOnMainThread(final Callable<T> callable, long timeoutMs) throws Exception {
        if (Thread.currentThread().getName().contains("Server thread")) {
            return callable.call();
        }
        final AtomicReference<T> resRef = new AtomicReference<>(null);
        final AtomicReference<Exception> errRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        this.mod.queueMain(new Runnable() {
            @Override
            public void run() {
                try {
                    resRef.set(callable.call());
                } catch (Exception e) {
                    errRef.set(e);
                } finally {
                    latch.countDown();
                }
            }
        });
        latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        if (errRef.get() != null) {
            throw errRef.get();
        }
        return resRef.get();
    }

    /**
     * Clears controller task state and every live action context.
     */
    public void clearActionState(DroneController ctrl) {
        if (ctrl == null) return;
        this.actionRegistry.cancelAll(ctrl);
        this.navigationManager.clear(ctrl);
        ctrl.clearActionState();
    }

    public void setDroneMode(DroneController ctrl, Mode newMode, String reason) {
        if (ctrl == null) return;
        Mode old = ctrl.mode;
        // A real mode transition means the previous task is over. Any parked
        // self-rescue queue is merged back so internal transitions (DROP/DEPOSIT)
        // can resume mining; explicit new commands reset queues themselves.
        if (newMode != old) {
            ctrl.clearSelfRescue(true);
        }
        ctrl.mode = newMode;
        ctrl.pathWaypoints.clear();
        ctrl.pathIndex = 0;
        ctrl.pathGoalX = Double.NaN;
        if (newMode != Mode.FOLLOW) {
            ctrl.hasAnchor = false;
        }
        if (newMode != Mode.MINE && ctrl.currentMineTarget != null && ctrl.drone != null && ctrl.drone.worldObj != null) {
            ctrl.drone.worldObj.destroyBlockInWorldPartially(
                    ctrl.drone.getEntityId(),
                    ctrl.currentMineTarget[0],
                    ctrl.currentMineTarget[1],
                    ctrl.currentMineTarget[2],
                    -1
            );
            ctrl.currentMineTarget = null;
        }
        if (old != newMode) {
            this.logger.log("MODE", ctrl, old.name() + " -> " + newMode.name(), reason != null ? reason : "");
            if (newMode == Mode.FOLLOW || newMode == Mode.STAY) {
                saveConfig();
            }
        }
    }

    public void onPlayerChat(ServerChatEvent event) {
        String msg = event.message != null ? event.message.trim() : "";
        String senderName = event.player.getCommandSenderName();
        this.logger.recordChat(senderName, msg);

        String lower = msg.toLowerCase();
        String prompt = null;
        if (lower.startsWith("@drone ")) {
            int idx = msg.indexOf(' ');
            prompt = idx > 0 ? msg.substring(idx + 1).trim() : "status";
        } else {
            DroneController owned = getPlayerDrone(senderName);
            if (owned != null && lower.startsWith("@" + owned.name.toLowerCase())) {
                prompt = msg.substring(owned.name.length() + 1).trim();
            }
        }

        if (prompt != null && !prompt.isEmpty()) {
            handleNaturalInstruction(event.player, prompt);
        }
    }

    public boolean ensureMachineRunningState(Drone drone) {
        return this.ocHardwareBridge.ensureMachineRunningState(drone);
    }

    public void onServerTickStart() {
        this.tickProfiler.onTickStart();
        for (DroneController ctrl : this.controllersById.values()) {
            if (ctrl.drone != null && !ctrl.drone.isDead) {
                ensureMachineRunningState(ctrl.drone);
            }
        }
    }

    public void onServerTickEnd() {
        this.rpcDispatcher.onServerTickEnd();
        this.tickCounter++;
        if (this.tickCounter % 40 == 0) {
            reconnectUnlinkedWorldDrones();
        }

        Iterator<Map.Entry<Integer, DroneController>> it = this.controllersById.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, DroneController> entry = it.next();
            DroneController ctrl = entry.getValue();
            if (ctrl.drone == null || ctrl.drone.isDead) {
                it.remove();
                continue;
            }
            try {
                this.tickManager.tickController(ctrl);
            } catch (Throwable t) {
                if (this.tickCounter % 40 == 0) {
                    this.logger.log("ERROR", ctrl, "Exception in tickController: " + t,
                            t.getStackTrace().length > 0 ? t.getStackTrace()[0].toString() : "");
                }
            }
        }

        this.tickProfiler.onTickEnd(this.tickCounter);

        // Snapshot cadence: every 100 ticks (5 s). /state & /debug serve from this cache directly
        // instead of rebuilding on demand - the rebuild was serializing the whole state JSON on
        // the server thread every 500 ms and on every /state HTTP hit, starving 20 TPS.
        if (this.tickCounter % 100 == 0) {
            this.httpServer.updateCachedSnapshots();
        }
    }

    private void reconnectUnlinkedWorldDrones() {
        MinecraftServer srv = MinecraftServer.getServer();
        if (srv == null || srv.worldServers == null) return;

        for (WorldServer ws : srv.worldServers) {
            if (ws == null || ws.loadedEntityList == null) continue;
            List<?> entities = new ArrayList<Object>((java.util.Collection<?>) ws.loadedEntityList);
            for (Object obj : entities) {
                if (obj instanceof Drone) {
                    Drone d = (Drone) obj;
                    if (d.isDead) continue;
                    if (findControllerByEntity(d) != null) continue;
                    if (wasDespawned(d)) continue;

                    UUID uid = d.getUniqueID();
                    String owner = d.ownerName() != null && !d.ownerName().isEmpty() ? d.ownerName() : "Server";
                    String name = d.getCommandSenderName() != null ? d.getCommandSenderName() : "AI-Drone";
                    Mode restoredMode = Mode.FOLLOW;
                    boolean restoredTorch = false;
                    String restoredLedEffect = "NONE";
                    Integer restoredCustomLed = null;

                    if (this.config.getSavedDrones() != null) {
                        AIConfig.SavedDroneInfo matchedSaved = null;
                        for (AIConfig.SavedDroneInfo s : this.config.getSavedDrones()) {
                            if (uid != null && uid.toString().equals(s.getUuid())) {
                                matchedSaved = s;
                                break;
                            }
                        }
                        if (matchedSaved == null && !"Server".equalsIgnoreCase(owner)) {
                            for (AIConfig.SavedDroneInfo s : this.config.getSavedDrones()) {
                                if (owner.equalsIgnoreCase(s.getOwner())) {
                                    matchedSaved = s;
                                    break;
                                }
                            }
                        }
                        if (matchedSaved != null) {
                            if (matchedSaved.getName() != null && !matchedSaved.getName().isEmpty())
                                name = matchedSaved.getName();
                            if (matchedSaved.getOwner() != null && !matchedSaved.getOwner().isEmpty())
                                owner = matchedSaved.getOwner();
                            if (matchedSaved.getMode() != null) {
                                try {
                                    restoredMode = Mode.valueOf(matchedSaved.getMode());
                                } catch (Exception ignored) {
                                }
                            }
                            restoredTorch = matchedSaved.isAutoTorch();
                            if (matchedSaved.getLedEffect() != null) restoredLedEffect = matchedSaved.getLedEffect();
                            restoredCustomLed = matchedSaved.getCustomLedColor();
                        }
                    }

                    // Deduplicate if this player already has an active drone controller
                    if (!"Server".equalsIgnoreCase(owner)) {
                        DroneController existingOwnerCtrl = getPlayerDrone(owner);
                        if (existingOwnerCtrl != null && existingOwnerCtrl.drone != null && !existingOwnerCtrl.drone.isDead && existingOwnerCtrl.drone != d) {
                            InventoryService.withdrawFromInventory(d.mainInventory(), existingOwnerCtrl.drone.mainInventory(), "all", 512);
                            d.setDead();
                            this.logger.log("LINK", existingOwnerCtrl, "Merged and despawned duplicate world drone for " + owner, "");
                            continue;
                        }
                    }

                    d.setName(name);
                    ensureMachineRunningState(d);
                    d.inventorySize_$eq(8);
                    DroneController ctrl = registerDroneController(d, name, owner);
                    ctrl.mode = restoredMode;
                    ctrl.autoTorch = restoredTorch;
                    ctrl.ledEffect = restoredLedEffect;
                    ctrl.customLedColor = restoredCustomLed;
                    this.logger.log("LINK", ctrl, "Auto-reconnected world drone",
                            String.format("owner=%s pos=(%.1f,%.1f,%.1f) mode=%s", owner, d.posX, d.posY, d.posZ, restoredMode.name()));
                }
            }
        }
    }

    public DroneController findControllerByEntity(Drone d) {
        UUID uid = d.getUniqueID();
        for (DroneController c : this.controllersById.values()) {
            if (c.drone == d || (uid != null && uid.equals(c.entityUuid))) {
                c.drone = d;
                return c;
            }
        }
        return null;
    }

    public DroneController getPlayerDrone(String playerName) {
        if (playerName == null) return null;
        DroneController ctrl = this.controllersByOwner.get(CortexDroneMod.normKey(playerName));
        if (ctrl != null && ctrl.drone != null && !ctrl.drone.isDead) {
            return ctrl;
        }
        for (DroneController c : this.controllersById.values()) {
            if (c.drone != null && !c.drone.isDead && playerName.equalsIgnoreCase(c.ownerName)) {
                this.controllersByOwner.put(CortexDroneMod.normKey(playerName), c);
                return c;
            }
        }
        return null;
    }

    public DroneController getOrAnyDrone(String playerName) {
        DroneController owned = getPlayerDrone(playerName);
        if (owned != null) return owned;
        for (DroneController c : this.controllersById.values()) {
            if (c.drone != null && !c.drone.isDead) {
                return c;
            }
        }
        return null;
    }

    public DroneController registerDroneController(Drone drone, String name, String ownerName) {
        int id = this.nextDroneId.getAndIncrement();
        DroneController ctrl = new DroneController(id, drone, name, ownerName);
        this.controllersById.put(id, ctrl);
        if (ownerName != null && !"Server".equalsIgnoreCase(ownerName)) {
            this.controllersByOwner.put(CortexDroneMod.normKey(ownerName), ctrl);
        }
        return ctrl;
    }

    public DroneController spawnDroneForPlayer(EntityPlayerMP player, String customName) {
        DroneController existing = getPlayerDrone(player.getCommandSenderName());
        if (existing != null && existing.drone != null && !existing.drone.isDead) {
            despawnDrone(existing, player);
        }

        World world = player.worldObj;
        Drone drone = new Drone(world);
        String dName = (customName != null && !customName.trim().isEmpty())
                ? customName.trim()
                : (player.getCommandSenderName() + "-AI");

        drone.setName(dName);
        drone.ownerName_$eq(player.getCommandSenderName());
        drone.ownerUUID_$eq(player.getUniqueID());

        double yawRad = Math.toRadians(player.rotationYaw);
        double fx = -Math.sin(yawRad) * 2.0;
        double fz = Math.cos(yawRad) * 2.0;
        double sx = player.posX + fx;
        double sy = player.posY + 1.25;
        double sz = player.posZ + fz;
        if (!ItemClassifier.isPassableVoxel(world, MathHelper.floor_double(sx), MathHelper.floor_double(sy), MathHelper.floor_double(sz))) {
            sx = player.posX;
            sy = player.posY + 1.5;
            sz = player.posZ;
        }

        drone.setPosition(sx, sy, sz);
        drone.preparePowerUp();
        drone.inventorySize_$eq(8);
        drone.lightColor_$eq(0x00FFFF);
        drone.statusText_$eq("Following\n" + trimStatus(player.getCommandSenderName()));

        FlightPathfinder.setDroneFlightTarget(drone, sx, sy, sz, 0.1f);
        ensureMachineRunningState(drone);
        world.spawnEntityInWorld(drone);

        DroneController ctrl = registerDroneController(drone, dName, player.getCommandSenderName());
        saveConfig();
        this.logger.log("SPAWN", ctrl, "Spawned new AI drone for " + player.getCommandSenderName(),
                String.format("pos=(%.1f,%.1f,%.1f)", sx, sy, sz));
        return ctrl;
    }

    public DroneController linkNearestDrone(EntityPlayerMP player) {
        World world = player.worldObj;
        AxisAlignedBB box = player.boundingBox.expand(8.0, 8.0, 8.0);
        List<?> list = world.getEntitiesWithinAABB(Drone.class, box);
        Drone best = null;
        double bestDist = Double.MAX_VALUE;
        for (Object o : list) {
            if (o instanceof Drone) {
                Drone d = (Drone) o;
                if (d.isDead) continue;
                double dist = d.getDistanceToEntity(player);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = d;
                }
            }
        }
        if (best == null) return null;

        DroneController ctrl = findControllerByEntity(best);
        String pName = player.getCommandSenderName();
        best.ownerName_$eq(pName);
        best.ownerUUID_$eq(player.getUniqueID());
        ensureMachineRunningState(best);
        best.inventorySize_$eq(8);
        best.lightColor_$eq(0x00FF88);
        best.statusText_$eq("Linked\n" + trimStatus(pName));

        if (ctrl == null) {
            String dName = best.getCommandSenderName() != null && !best.getCommandSenderName().isEmpty()
                    ? best.getCommandSenderName()
                    : (pName + "-AI");
            ctrl = registerDroneController(best, dName, pName);
        } else {
            ctrl.ownerName = pName;
            ctrl.targetPlayerName = pName;
            this.controllersByOwner.put(CortexDroneMod.normKey(pName), ctrl);
        }
        saveConfig();
        this.logger.log("LINK", ctrl, "Linked drone to " + pName, String.format("dist=%.2f", bestDist));
        return ctrl;
    }

    private boolean wasDespawned(Drone d) {
        return d != null && d.getUniqueID() != null && this.despawnedUuids.contains(d.getUniqueID());
    }

    public int despawnDrone(DroneController ctrl, EntityPlayerMP dropTargetPlayer) {
        if (ctrl == null || ctrl.drone == null) return 0;
        Drone d = ctrl.drone;
        World w = d.worldObj;
        double dx = dropTargetPlayer != null ? dropTargetPlayer.posX : d.posX;
        double dy = dropTargetPlayer != null ? dropTargetPlayer.posY + 0.5 : d.posY;
        double dz = dropTargetPlayer != null ? dropTargetPlayer.posZ : d.posZ;

        int droppedCount = InventoryService.dropAllDroneItemsAt(d, w, dx, dy, dz);
        this.visualsManager.clearLighting(ctrl);
        this.navigationManager.clear(ctrl);
        this.breadcrumbService.clear(ctrl);
        ctrl.despawned = true;
        UUID duid = d.getUniqueID();
        if (duid != null) {
            this.despawnedUuids.add(duid);
            if (this.config != null && this.config.getSavedDrones() != null) {
                Iterator<AIConfig.SavedDroneInfo> sit = this.config.getSavedDrones().iterator();
                while (sit.hasNext()) {
                    AIConfig.SavedDroneInfo s = sit.next();
                    if (s != null && duid.toString().equals(s.getUuid())) sit.remove();
                }
            }
        }
        d.setDead();
        this.controllersById.remove(ctrl.id);
        this.httpServer.updateCachedSnapshots();
        if (ctrl.ownerName != null) {
            String key = CortexDroneMod.normKey(ctrl.ownerName);
            this.controllersByOwner.remove(key, ctrl);
            for (DroneController other : this.controllersById.values()) {
                if (other != null && other.drone != null && !other.drone.isDead
                        && ctrl.ownerName.equalsIgnoreCase(other.ownerName)) {
                    this.controllersByOwner.put(key, other);
                    break;
                }
            }
        }
        saveConfig();
        this.logger.log("DESPAWN", ctrl, "Despawned drone cleanly", "droppedItems=" + droppedCount);
        return droppedCount;
    }

    public static String trimStatus(String s) {
        if (s == null) return "";
        return s.length() > 10 ? s.substring(0, 10) : s;
    }

    public void droneSay(ICommandSender recipient, DroneController ctrl, String message) {
        String dName = ctrl != null && ctrl.name != null ? ctrl.name : "AI";
        String formatted = EnumChatFormatting.AQUA + "[Drone:" + dName + "] " + EnumChatFormatting.WHITE + message;
        this.logger.recordChat("Drone:" + dName, message);
        if (recipient != null) {
            CortexDroneMod.sendMsg(recipient, formatted);
        } else {
            CortexDroneMod.broadcast(formatted);
        }
    }

    public void sendDroneStatus(ICommandSender sender, DroneController ctrl) {
        Drone d = ctrl.drone;
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.GOLD + "=== AI Drone Status [" + EnumChatFormatting.AQUA + ctrl.name + EnumChatFormatting.GOLD + "] ===");
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.YELLOW + "ID: " + EnumChatFormatting.WHITE + "#" + ctrl.id
                + EnumChatFormatting.YELLOW + " | Owner: " + EnumChatFormatting.WHITE + ctrl.ownerName
                + EnumChatFormatting.YELLOW + " | Mode: " + EnumChatFormatting.GREEN + ctrl.mode.name()
                + (ctrl.playerLookHold ? EnumChatFormatting.AQUA + " [LOOK-HOLD]" : ""));
        CortexDroneMod.sendMsg(sender, String.format(EnumChatFormatting.YELLOW + "Pos: " + EnumChatFormatting.WHITE + "(%.1f, %.1f, %.1f)"
                        + EnumChatFormatting.YELLOW + " -> Target: " + EnumChatFormatting.WHITE + "(%.1f, %.1f, %.1f)"
                        + EnumChatFormatting.GRAY + " [Waypoints: %d/%d]",
                d.posX, d.posY, d.posZ, (double) d.targetX(), (double) d.targetY(), (double) d.targetZ(),
                ctrl.pathIndex, ctrl.pathWaypoints.size()));
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.YELLOW + "LED: " + EnumChatFormatting.AQUA + String.format("#%06X", d.lightColor() & 0xFFFFFF)
                + EnumChatFormatting.YELLOW + " | Light Aura: " + EnumChatFormatting.WHITE + (ctrl.autoTorch ? "ON" : "OFF")
                + (ctrl.autoTorch ? " (#" + String.format("%06X", ctrl.lightColor & 0xFFFFFF) + ", r=" + ctrl.lightRadius + ", level=" + ctrl.lightIntensity + ")" : "")
                + EnumChatFormatting.YELLOW + " | Energy: " + EnumChatFormatting.GREEN + d.globalBuffer() + "/" + d.globalBufferSize());
        CortexDroneMod.sendMsg(sender, EnumChatFormatting.YELLOW + "Inventory (8 slots): " + EnumChatFormatting.WHITE + InventoryService.formatInventorySummary(d.mainInventory()));
    }

    public void handleNaturalInstruction(final EntityPlayerMP player, final String prompt) {
        if (!isServerThread()) {
            dispatchToServerThread(new Runnable() {
                @Override
                public void run() {
                    handleNaturalInstruction(player, prompt);
                }
            });
            return;
        }
        DroneController ctrl = player != null ? getPlayerDrone(player.getCommandSenderName()) : null;
        if (ctrl == null && player != null) {
            ctrl = linkNearestDrone(player);
            if (ctrl == null) {
                ctrl = spawnDroneForPlayer(player, null);
                droneSay(player, ctrl, "Spawned and linked! Ready for your command.");
            }
        }
        handleNaturalInstruction(player, ctrl, prompt);
    }

    public void handleNaturalInstruction(final EntityPlayerMP player, final DroneController ctrl, final String prompt) {
        if (!isServerThread()) {
            dispatchToServerThread(new Runnable() {
                @Override
                public void run() {
                    handleNaturalInstruction(player, ctrl, prompt);
                }
            });
            return;
        }
        this.taskCoordinator.handle(player, ctrl, prompt);
    }

    /**
     * World reads, chunk access and drone mutation all belong on the server thread.
     */
    private static boolean isServerThread() {
        return Thread.currentThread().getName().contains("Server thread");
    }

    private void dispatchToServerThread(final Runnable task) {
        try {
            this.callOnMainThread(new java.util.concurrent.Callable<Void>() {
                @Override
                public Void call() {
                    task.run();
                    return null;
                }
            }, 5000L);
        } catch (Exception e) {
            this.logger.log("ERROR", null, "Failed to dispatch instruction to server thread", e.toString());
        }
    }
}
