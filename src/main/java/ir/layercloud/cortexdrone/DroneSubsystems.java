package ir.layercloud.cortexdrone;

import ir.layercloud.cortexdrone.config.DroneLogger;
import ir.layercloud.cortexdrone.harness.task.TaskCoordinator;
import ir.layercloud.cortexdrone.manager.ActionRegistry;
import ir.layercloud.cortexdrone.manager.TickManager;
import ir.layercloud.cortexdrone.manager.building.impl.BuildingManager;
import ir.layercloud.cortexdrone.manager.chunk.impl.ChunkLoadingManager;
import ir.layercloud.cortexdrone.manager.interact.impl.InteractionManager;
import ir.layercloud.cortexdrone.manager.inventory.impl.InventoryManager;
import ir.layercloud.cortexdrone.manager.navigation.impl.NavigationManager;
import ir.layercloud.cortexdrone.manager.oc.impl.OcManager;
import ir.layercloud.cortexdrone.manager.visuals.impl.VisualsManager;
import ir.layercloud.cortexdrone.service.inventory.InventoryService;
import ir.layercloud.cortexdrone.service.navigation.impl.BreadcrumbService;
import ir.layercloud.cortexdrone.service.navigation.impl.FlightPathfinder;
import ir.layercloud.cortexdrone.service.world.DamageJournal;
import ir.layercloud.cortexdrone.service.world.DocLookupService;
import ir.layercloud.cortexdrone.service.world.WorldScanner;

/**
 * The long-lived manager/service graph one {@link AIDroneManager} owns.
 *
 * <p>Extracted from the manager's constructor. Wiring is still explicit — no
 * container, no reflection — but it lives in one place whose only job is
 * construction, so the manager classes read as behaviour instead of
 * dependency assembly.</p>
 */
final class DroneSubsystems {
    final FlightPathfinder pathfinder;
    final BreadcrumbService breadcrumbService;
    final ChunkLoadingManager chunkLoadingManager;
    final WorldScanner worldScanner;
    final InventoryService inventoryService;
    final VisualsManager visualsManager;
    final InteractionManager interactionManager;
    final InventoryManager inventoryManager;
    final BuildingManager buildingManager;
    final OcManager ocManager;
    final NavigationManager navigationManager;
    final ActionRegistry actionRegistry;
    final DocLookupService docLookupService;
    final TickManager tickManager;
    final TickProfiler tickProfiler;
    final TaskCoordinator taskCoordinator;

    private DroneSubsystems(Builder builder) {
        this.pathfinder = builder.pathfinder;
        this.breadcrumbService = builder.breadcrumbService;
        this.chunkLoadingManager = builder.chunkLoadingManager;
        this.worldScanner = builder.worldScanner;
        this.inventoryService = builder.inventoryService;
        this.visualsManager = builder.visualsManager;
        this.interactionManager = builder.interactionManager;
        this.inventoryManager = builder.inventoryManager;
        this.buildingManager = builder.buildingManager;
        this.ocManager = builder.ocManager;
        this.navigationManager = builder.navigationManager;
        this.actionRegistry = builder.actionRegistry;
        this.docLookupService = builder.docLookupService;
        this.tickManager = builder.tickManager;
        this.tickProfiler = builder.tickProfiler;
        this.taskCoordinator = builder.taskCoordinator;
    }

    /**
     * Builds the graph for one manager.
     */
    static DroneSubsystems create(AIDroneManager manager, CortexDroneMod mod,
                                  DroneLogger logger, DamageJournal damageJournal) {
        return new Builder(manager, mod, logger, damageJournal).build();
    }

    private static final class Builder {
        private final AIDroneManager manager;
        private final CortexDroneMod mod;
        private final DroneLogger logger;
        private final DamageJournal damageJournal;

        FlightPathfinder pathfinder;
        BreadcrumbService breadcrumbService;
        ChunkLoadingManager chunkLoadingManager;
        WorldScanner worldScanner;
        InventoryService inventoryService;
        VisualsManager visualsManager;
        InteractionManager interactionManager;
        InventoryManager inventoryManager;
        BuildingManager buildingManager;
        OcManager ocManager;
        NavigationManager navigationManager;
        ActionRegistry actionRegistry;
        DocLookupService docLookupService;
        TickManager tickManager;
        TickProfiler tickProfiler;
        TaskCoordinator taskCoordinator;

        Builder(AIDroneManager manager, CortexDroneMod mod, DroneLogger logger,
                DamageJournal damageJournal) {
            this.manager = manager;
            this.mod = mod;
            this.logger = logger;
            this.damageJournal = damageJournal;
        }

        DroneSubsystems build() {
            this.pathfinder = new FlightPathfinder(this.logger);
            this.breadcrumbService = new BreadcrumbService();
            this.chunkLoadingManager = new ChunkLoadingManager(this.manager, this.mod, this.logger);
            this.worldScanner = new WorldScanner(this.logger, this.manager.getConfig());
            this.inventoryService = new InventoryService(this.logger);
            this.visualsManager = new VisualsManager(this.manager, this.logger);
            this.interactionManager = new InteractionManager(this.inventoryService, this.logger);
            this.inventoryManager = new InventoryManager(this.manager, this.inventoryService, this.logger);
            this.buildingManager = new BuildingManager(this.logger, this.damageJournal);
            this.ocManager = new OcManager();
            this.navigationManager = new NavigationManager(this.manager, this.logger,
                    this.pathfinder, this.breadcrumbService, this.chunkLoadingManager);

            this.actionRegistry = new ActionRegistry();
            this.actionRegistry.register(this.buildingManager);
            this.actionRegistry.register(this.interactionManager);
            this.actionRegistry.register(this.inventoryManager);
            this.actionRegistry.register(this.visualsManager);
            this.actionRegistry.register(this.ocManager);

            this.docLookupService = new DocLookupService(this.inventoryService);
            this.tickManager = new TickManager(this.manager, this.logger,
                    this.navigationManager, this.breadcrumbService);
            this.tickProfiler = new TickProfiler(this.logger);
            this.taskCoordinator = new TaskCoordinator(this.manager);
            return new DroneSubsystems(this);
        }
    }
}
