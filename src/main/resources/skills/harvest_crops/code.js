//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Crop harvest + replant. params: { crop, radius, limit, replant }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var crop = String(cfg.crop || 'wheat').toLowerCase();
var profiles = {
    wheat: {block: 'wheat', seed: 'wheat seeds', mature: 7, item: 'wheat'},
    carrots: {block: 'carrots', seed: 'carrot', mature: 7, item: 'carrot'},
    carrot: {block: 'carrots', seed: 'carrot', mature: 7, item: 'carrot'},
    potatoes: {block: 'potatoes', seed: 'potato', mature: 7, item: 'potato'},
    potato: {block: 'potatoes', seed: 'potato', mature: 7, item: 'potato'}
};
var profile = profiles[crop];
if (!profile) {
    return {
        ok: false, error: 'unsupported crop "' + crop + '"',
        hint: 'use wheat, carrots or potatoes'
    };
}

var radius = clamp(cfg.radius || LIMITS.scanRadiusDefault, LIMITS.blockScanRadiusMin, LIMITS.smallScanRadiusMax);
var limit = clamp(cfg.limit || 16, 1, SKILL_LIMITS.smallCountCap);
var replant = cfg.replant !== false;

var found = drone.scanBlocks({filter: profile.block, radius: radius});
if (!found || !found.count) {
    return {
        ok: false, error: 'no ' + crop + ' within ' + radius + 'm',
        hint: 'fly to the farm or widen the radius'
    };
}

var mature = [];
var growing = 0;
var list = found.blocks || [];
for (var i = 0; i < list.length && mature.length < limit; i++) {
    var block = list[i];
    if (Number(block.meta) >= profile.mature) mature.push(block);
    else growing++;
}
if (!mature.length) {
    return {
        ok: true, crop: crop, harvested: 0, growing: growing,
        note: 'crops are still growing; come back later'
    };
}

var harvested = 0;
var replanted = 0;
var failed = 0;
for (var h = 0; h < mature.length; h++) {
    var plant = mature[h];
    try {
        drone.goto({standoffOf: {x: plant.x, y: plant.y, z: plant.z}, allow_dig: false});
        var broke = drone.breakBlock({x: plant.x, y: plant.y, z: plant.z});
        if (!broke || !broke.broken) {
            failed++;
            continue;
        }
        harvested++;
    } catch (breakError) {
        failed++;
        log('harvest skipped at ' + plant.x + ',' + plant.y + ',' + plant.z + ': ' + breakError.message);
        continue;
    }

    if (!replant) continue;
    try {
        var inventory = drone.state().inventory;
        if (countInInventory(inventory, profile.seed) <= 0) continue;
        drone.goto({standoffOf: {x: plant.x, y: plant.y - 1, z: plant.z}, allow_dig: false});
        var used = drone.useItem({item: profile.seed, x: plant.x, y: plant.y - 1, z: plant.z, side: 1});
        if (used && used.used) replanted++;
    } catch (plantError) {
        log('replant skipped: ' + plantError.message);
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

var after = drone.state();
return {
    ok: harvested > 0,
    crop: crop,
    harvested: harvested,
    replanted: replanted,
    failed: failed,
    growing: growing,
    seedsLeft: countInInventory(after.inventory, profile.seed),
    collected: collected && collected.collected,
    hint: replant && replanted < harvested
        ? 'bring seeds to replant every harvested crop; light must be 9+ for the seed to survive'
        : ''
};
