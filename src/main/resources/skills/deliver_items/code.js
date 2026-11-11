//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Delivery primitive. params: { filter, count, player, only_if_holding }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var filter = cfg.filter || 'all';
var count = clamp(cfg.count || LIMITS.defaultTransferCount, 1, MAX_TRANSFER_COUNT);
var playerName = cfg.player;
var state = drone.state();
var holding = countInInventory(state.inventory, filter);

if (cfg.only_if_holding !== false && holding <= 0) {
    return {
        ok: false, error: 'nothing matching "' + filter + '" in the drone inventory',
        hint: 'mine, harvest or withdraw the items first'
    };
}

var player = drone.getPlayer(playerName ? {name: playerName} : {});
if (!player || player.ok === false) {
    return {ok: false, error: 'player is offline or unavailable'};
}

var moved = drone.goto({x: player.blockX, y: player.blockY + 2, z: player.blockZ, allow_dig: false});
if (!moved || moved.arrived === false) {
    return {ok: false, error: 'could not reach the player', navigation: moved};
}

var given = drone.giveToPlayer({filter: filter, count: count, player: playerName});
return {
    ok: true,
    filter: filter,
    given: (given && given.transferred) || 0,
    player: player.name || playerName || '',
    holdingBefore: holding
};
