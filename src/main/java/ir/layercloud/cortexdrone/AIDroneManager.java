package ir.layercloud.cortexdrone;

import cpw.mods.fml.common.event.FMLServerStartingEvent;
import ir.layercloud.cortexdrone.command.impl.DroneCommand;
import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.config.ModConfig;
import ir.layercloud.cortexdrone.config.ModPaths;
import ir.layercloud.cortexdrone.config.WorldDirectory;
import ir.layercloud.cortexdrone.harness.Harness;
import ir.layercloud.cortexdrone.harness.agent.AgentService;
import ir.layercloud.cortexdrone.harness.agent.LoopTracer;
import ir.layercloud.cortexdrone.harness.js.JsEngine;
import ir.layercloud.cortexdrone.harness.session.DroneSession;
import ir.layercloud.cortexdrone.harness.session.SessionManager;
import ir.layercloud.cortexdrone.harness.session.SessionOutcome;
import ir.layercloud.cortexdrone.harness.skill.SkillLibrary;
import ir.layercloud.cortexdrone.harness.task.TaskCoordinator;
import ir.layercloud.cortexdrone.harness.tool.MainThreadCall;
import ir.layercloud.cortexdrone.harness.tool.Threads;
import ir.layercloud.cortexdrone.manager.ActionRegistry;
import ir.layercloud.cortexdrone.manager.TickManager;
import ir.layercloud.cortexdrone.manager.building.impl.BuildingManager;
import ir.layercloud.cortexdrone.manager.chunk.impl.ChunkLoadingManager;
import ir.layercloud.cortexdrone.manager.interact.impl.InteractionManager;
import ir.layercloud.cortexdrone.manager.inventory.impl.InventoryManager;
import ir.layercloud.cortexdrone.manager.navigation.impl.NavigationManager;
import ir.layercloud.cortexdrone.manager.oc.impl.OcManager;
import ir.layercloud.cortexdrone.manager.visuals.impl.VisualsManager;
import ir.layercloud.cortexdrone.network.QuestionNetwork;
import ir.layercloud.cortexdrone.oc.OcHardwareBridge;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.inventory.ContainerCloser;
import ir.layercloud.cortexdrone.service.inventory.TransferAnimation;
import ir.layercloud.cortexdrone.service.navigation.impl.BreadcrumbService;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import ir.layercloud.cortexdrone.service.question.QuestionService;
import ir.layercloud.cortexdrone.service.question.ServerQuestionTransport;
import ir.layercloud.cortexdrone.service.text.TextSanitizer;
import ir.layercloud.cortexdrone.service.world.DamageJournal;
import ir.layercloud.cortexdrone.service.world.DocLookupService;
import ir.layercloud.cortexdrone.service.world.ProtectionZones;
import ir.layercloud.cortexdrone.service.world.WorldScanner;
import li.cil.oc.common.entity.Drone;
import lombok.Getter;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.event.ServerChatEvent;

import java.io.File;
import java.util.Collection;
import java.util.concurrent.Callable;

@Getter
@SuppressWarnings("unused")
public class AIDroneManager {
    private final CortexDroneMod mod;
    private File configRoot;
    private File configFile;
    private File worldRoot;
    private ModConfig config = new ModConfig();

    private final DroneLogger logger = new DroneLogger();
    private final SessionManager sessionManager = new SessionManager();
    private final FlightPathfinder pathfinder;
    private final BreadcrumbService breadcrumbService;
    private final ChunkLoadingManager chunkLoadingManager;
    private final NavigationManager navigationManager;
    private final WorldScanner worldScanner;
    private final InventoryService inventoryService;
    private final BuildingManager buildingManager;
    private final VisualsManager visualsManager;
    private final InteractionManager interactionManager;
    private final InventoryManager inventoryManager;
    private final OcManager ocManager;
    private final ActionRegistry actionRegistry;
    private final DocLookupService docLookupService;
    private Harness harness;
    private final TickManager tickManager;
    private final TickProfiler tickProfiler;
    private final TaskCoordinator taskCoordinator;
    private final QuestionService questionService;

    /**
     * -- GETTER --
     * The controller registry; read-only to callers.
     */
    private final DroneRegistry registry = new DroneRegistry();
    private final OcHardwareBridge ocHardwareBridge = new OcHardwareBridge();
    private final DamageJournal damageJournal = new DamageJournal();
    private final DroneReconnector reconnector;
    private final DroneLifecycle lifecycle;
    private final DroneTickLoop tickLoop;
    private final InstructionRouter instructions;

    public AIDroneManager(CortexDroneMod mod, File configRoot) {
        this.mod = mod;
        this.configRoot = ModPaths.ensure(configRoot);
        DroneSubsystems subsystems = DroneSubsystems.create(this, mod, this.logger, this.damageJournal);
        this.pathfinder = subsystems.pathfinder;
        this.breadcrumbService = subsystems.breadcrumbService;
        this.chunkLoadingManager = subsystems.chunkLoadingManager;
        this.worldScanner = subsystems.worldScanner;
        this.inventoryService = subsystems.inventoryService;
        this.visualsManager = subsystems.visualsManager;
        this.interactionManager = subsystems.interactionManager;
        this.inventoryManager = subsystems.inventoryManager;
        this.buildingManager = subsystems.buildingManager;
        this.ocManager = subsystems.ocManager;
        this.navigationManager = subsystems.navigationManager;
        this.actionRegistry = subsystems.actionRegistry;
        this.docLookupService = subsystems.docLookupService;
        this.tickManager = subsystems.tickManager;
        this.tickProfiler = subsystems.tickProfiler;
        this.taskCoordinator = subsystems.taskCoordinator;
        this.lifecycle = new DroneLifecycle(new LifecycleHost(this));
        this.reconnector = new DroneReconnector(new ReconnectHost(this));
        this.tickLoop = new DroneTickLoop(new TickLoopHost(this));
        this.instructions = new InstructionRouter(new InstructionHost(this));
        this.questionService = new QuestionService(new ServerQuestionTransport(this));
        QuestionNetwork.bind(this.questionService);
    }

    /** Releases everything that holds per-drone state. */
    /**
     * Releases navigation, visuals, chunks, session and breadcrumb state.
     */
    void forgetController(DroneController controller) {
        this.visualsManager.forget(controller);
        this.navigationManager.forget(controller);
        this.chunkLoadingManager.releaseDroneTicket(controller);
        DroneSession session = this.sessionManager.session(controller);
        if (session != null) session.cancelOtherLoops(0, "drone_despawned");
        this.sessionManager.forget(controller);
        this.interactionManager.forget(controller);
        this.breadcrumbService.clear(controller);
    }

    public void onServerStarting(FMLServerStartingEvent event) {
        ModPaths.ensure(this.configRoot);
        this.configFile = ModPaths.configFile(this.configRoot);
        this.config = ModConfig.load(this.configFile, CortexDroneMod.GSON);
        File worldSave = WorldDirectory.resolve();
        this.worldRoot = worldSave != null ? ModPaths.worldRoot(worldSave) : this.configRoot;
        ProtectionZones.load(ModPaths.protectedZonesFile(this.worldRoot));
        this.chunkLoadingManager.attach();
        this.harness = Harness.create(this, this.config, new SkillLibrary(this.configRoot));
        this.logger.setDebugLogFile(ModPaths.logFile(this.configRoot));
        reconnectUnlinkedWorldDrones();
        this.logger.log("SYSTEM", null, "CortexDrone v" + CortexDroneMod.VERSION
                        + " (in-process tool harness) initialized",
                "configDir=" + this.configRoot.getAbsolutePath()
                        + " worldDir=" + (this.worldRoot != null ? this.worldRoot.getAbsolutePath() : "<none>")
                        + " tools=" + this.harness.registry().modelTools().size());

        event.registerServerCommand(new DroneCommand("drone", this));
    }

    public void onServerStopping() {
        this.logger.log("SYSTEM", null, "Server stopping; saving AI drone config", "");
        if (this.harness != null) {
            this.harness.journal().closeActive(SessionOutcome.INTERRUPTED, "server_stopping");
        }
        for (DroneController ctrl : this.registry.all()) {
            if (ctrl != null && !this.visualsManager.state(ctrl).lightCells.isEmpty()) {
                this.visualsManager.clearLighting(ctrl);
            }
        }
        this.chunkLoadingManager.shutdown();
        saveConfig();
    }

    public synchronized void reloadConfig() {
        if (this.harness != null) {
            this.harness.journal().closeActive(SessionOutcome.INTERRUPTED, "config_reload");
        }
        this.config = ModConfig.load(this.configFile, CortexDroneMod.GSON);
        if (this.worldRoot != null) {
            ProtectionZones.load(ModPaths.protectedZonesFile(this.worldRoot));
        }
        if (this.harness != null) {
            this.harness = Harness.create(this, this.config,
                    new SkillLibrary(this.configRoot));
        }
    }

    public synchronized void saveConfig() {
        if (this.configFile == null) return;
        this.config.save(this.configFile, CortexDroneMod.GSON, this.registry.all(),
                this.visualsManager::state);
    }

    public JsEngine getJsEngine() {
        return this.harness != null ? this.harness.js() : null;
    }

    public AgentService getAgentService() {
        return this.harness != null ? this.harness.agent() : null;
    }

    public <T> T callOnMainThread(final Callable<T> callable, long timeoutMs) throws Exception {
        if (Threads.isServerThread()) {
            return callable.call();
        }
        MainThreadCall.Result<T> result = MainThreadCall.call(this.mod::queueMain, callable, timeoutMs);
        if (result.error() != null) {
            if (result.error() instanceof Exception) throw (Exception) result.error();
            throw new RuntimeException(result.error());
        }
        // A timeout (or an abandoned task) returns null, which every caller
        // already treats as "did not finish".
        return result.timedOut() ? null : result.value();
    }

    /**
     * Clears controller task state and every live action context.
     */
    public void clearActionState(DroneController ctrl) {
        if (ctrl == null) return;
        this.actionRegistry.cancelAll(ctrl);
        this.navigationManager.clear(ctrl);
    }

    public boolean onPlayerChat(ServerChatEvent event) {
        // Player chat is untrusted: a modified client can send section signs,
        // control characters or non-BMP glyphs that mis-render chat and logs.
        String message = TextSanitizer.chat(event.message != null ? event.message : "");
        String playerName = event.player.getCommandSenderName();
        this.logger.recordChat(playerName, message);
        // A player with an open question answers by typing in chat when the
        // dialog is unavailable. Consume the line so it does not become a task.
        if (this.questionService.answerFromChat(playerName, message)) {
            this.logger.log("QUESTION", null, "Chat answer from " + playerName,
                    "text=\"" + LoopTracer.snippet(message, 200) + "\"");
            return true;
        }
        this.instructions.onChat(event.player, message);
        return false;
    }

    public boolean ensureMachineRunningState(Drone drone) {
        return this.ocHardwareBridge.ensureMachineRunningState(drone);
    }

    public void onServerTickStart() {
        this.tickLoop.onTickStart();
    }

    public void onServerTickEnd() {
        this.tickLoop.onTickEnd();
        // Container lids close a few ticks after the transfer that opened them;
        // opening and closing in one tick produced no visible animation.
        ContainerCloser.tick();
        // Item trails between containers and the drone.
        TransferAnimation.tick();
    }

    /**
     * Exposed for the tick loop and diagnostics.
     */
    boolean anyDroneBusy() {
        return this.tickLoop.anyDroneBusy();
    }

    private void reconnectUnlinkedWorldDrones() {
        reconnector.reconnectAll();
    }

    public DroneController findControllerByEntity(Drone d) {
        return this.lifecycle.findByEntity(d);
    }

    public DroneController getPlayerDrone(String playerName) {
        if (playerName == null) return null;
        DroneController indexed = this.registry.byOwner(playerName);
        if (indexed != null && indexed.drone != null && !indexed.drone.isDead) return indexed;
        // The index may be stale after a reload; repair it from the live list.
        for (DroneController candidate : this.registry.all()) {
            if (candidate.drone != null && !candidate.drone.isDead
                    && playerName.equalsIgnoreCase(candidate.ownerName)) {
                this.registry.index(candidate);
                return candidate;
            }
        }
        return null;
    }

    public DroneController registerDroneController(Drone drone, String name, String ownerName) {
        return this.lifecycle.register(drone, name, ownerName);
    }

    /**
     * Read-only view of every live controller.
     */
    public Collection<DroneController> controllers() {
        return this.registry.all();
    }

    /**
     * Live controller count, for status lines and diagnostics.
     */
    public int controllerCount() {
        return this.registry.size();
    }

    public DroneController spawnDroneForPlayer(EntityPlayerMP player, String customName) {
        return this.lifecycle.spawn(player, customName);
    }

    public DroneController linkNearestDrone(EntityPlayerMP player) {
        return this.lifecycle.linkNearest(player);
    }

    /**
     * True when the mod deliberately despawned this entity.
     */
    boolean wasDespawned(Drone drone) {
        return drone != null && this.registry.wasDespawned(drone.getUniqueID());
    }

    public int despawnDrone(DroneController controller, EntityPlayerMP dropTargetPlayer) {
        return this.lifecycle.despawn(controller, dropTargetPlayer);
    }

    /**
     * @see DroneStatusRenderer#trimStatus(String)
     */
    public static String trimStatus(String s) {
        return DroneStatusRenderer.trimStatus(s);
    }

    public void droneSay(ICommandSender recipient, DroneController ctrl, String message) {
        String name = ctrl != null && ctrl.name != null ? ctrl.name : "AI";
        String clean = TextSanitizer.chat(message);
        if (clean.isEmpty()) return;
        String formatted = DroneStatusRenderer.say(name, clean);
        this.logger.recordChat("Drone:" + name, clean);
        if (recipient != null) {
            CortexDroneMod.sendMsg(recipient, formatted);
        } else {
            CortexDroneMod.broadcast(formatted);
        }
    }

    public void sendDroneStatus(ICommandSender sender, DroneController ctrl) {
        DroneStatusRenderer.render(sender, ctrl, this.navigationManager,
                this.actionRegistry, this.visualsManager);
    }

    public void handleNaturalInstruction(EntityPlayerMP player, String prompt) {
        this.instructions.handle(player, prompt);
    }

    public void handleNaturalInstruction(EntityPlayerMP player, DroneController controller,
                                         String prompt) {
        this.instructions.handle(player, controller, prompt);
    }
}
