//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Return to player. params: { player, allow_dig }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var player = drone.getPlayer(cfg.player ? {name: cfg.player} : {});
if (!player || player.ok === false) {
    return {ok: false, error: 'player is offline or unavailable'};
}
try {
    var moved = drone.goto({
        x: player.blockX,
        y: player.blockY + 2,
        z: player.blockZ,
        allow_dig: cfg.allow_dig === true,
        max_wait_ms: LIMITS.maxWaitMs
    });
    return {
        ok: !!(moved && moved.arrived), arrived: !!(moved && moved.arrived),
        player: player.name || '', position: drone.state()
    };
} catch (navError) {
    return {
        ok: false, error: 'could not reach the player: ' + navError.message,
        player: player.name || ''
    };
}
