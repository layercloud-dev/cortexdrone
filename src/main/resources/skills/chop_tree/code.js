//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Tree felling. params: { radius, limit, replant }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var radius = clamp(cfg.radius || SKILL_LIMITS.chopRadiusDefault, LIMITS.blockScanRadiusMin, LIMITS.smallScanRadiusMax);
var limit = clamp(cfg.limit || 24, 1, SKILL_LIMITS.smallScanLimit);
var replant = cfg.replant !== false;

var found = drone.scanBlocks({filter: 'logs', radius: radius});
if (!found || !found.count) {
    return {
        ok: false, error: 'no logs within ' + radius + 'm',
        hint: 'fly closer to a tree or widen the radius'
    };
}

var logs = (found.blocks || []).slice(0, limit);
logs.sort(function (a, b) {
    return b.y - a.y;
}); // top-down

var broken = 0;
var failed = 0;
var pickedUp = 0;
var bases = {};
for (var i = 0; i < logs.length; i++) {
    var log = logs[i];
    var key = log.x + ',' + log.z;
    if (!bases[key] || log.y < bases[key].y) bases[key] = log;
    try {
        drone.goto({standoffOf: {x: log.x, y: log.y, z: log.z}, allow_dig: false});
        var broke = drone.breakBlock({x: log.x, y: log.y, z: log.z});
        if (broke && broke.broken) {
            broken++;
            if (cfg.collect !== false) {
                var loot = pickupNearbyLoot(SKILL_LIMITS.lootVacuumRadius, {walk: false});
                pickedUp += loot.pickedUp || 0;
            }
        } else failed++;
    } catch (breakError) {
        failed++;
        log('log skip: ' + breakError.message);
    }
}

var collected = null;
try {
    collected = skills.call('collect_drops', {
        radius: Math.min(LIMITS.collectRadiusMax, radius), filter: 'all',
        max_clusters: SKILL_LIMITS.maxClusters
    });
} catch (collectError) {
    log('drop collection skipped: ' + collectError.message);
}

var replanted = 0;
if (replant) {
    var inventory = drone.state().inventory;
    var saplings = countInInventory(inventory, 'sapling');
    var keys = Object.keys(bases);
    for (var s = 0; s < keys.length && replanted < saplings; s++) {
        var base = bases[keys[s]];
        try {
            drone.goto({standoffOf: {x: base.x, y: base.y - 1, z: base.z}, allow_dig: false});
            var used = drone.useItem({item: 'sapling', x: base.x, y: base.y - 1, z: base.z, side: 1});
            if (used && used.used) replanted++;
        } catch (plantError) {
            log('sapling skip: ' + plantError.message);
        }
    }
}

return {
    ok: broken > 0,
    logs: broken,
    failed: failed,
    pickedUp: pickedUp,
    saplingsPlanted: replanted,
    collected: collected && collected.collected,
    hint: broken > 0 ? '' : 'logs exist but were unreachable'
};
