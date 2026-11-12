//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Mine an exact box or relative volume. params:
// { x1,y1,z1,x2,y2,z2 } or { anchor, width, height, depth, length, filter, max_blocks }
// anchor: under_player | front_of_player | around_player
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var player = drone.getPlayer(cfg.player ? {name: cfg.player} : {});
if (!player || player.ok === false) {
    return {ok: false, error: 'player is offline or unavailable'};
}

var anchor = cfg.anchor || (cfg.x1 === undefined ? 'under_player' : 'box');
var width = clamp(cfg.width || 3, LIMITS.dimensionMin, LIMITS.siteSideMax);
var length = clamp(cfg.length || cfg.depth || 3, LIMITS.dimensionMin, LIMITS.siteSideMax);
var height = clamp(cfg.height || cfg.depth || 3, LIMITS.dimensionMin, LIMITS.siteSideMax);
var filter = cfg.filter || 'all';
var maxBlocks = clamp(cfg.max_blocks || SKILL_LIMITS.budget256, 1, SKILL_LIMITS.budget1600);
var includeLiquids = cfg.include_liquids === true;

var x1, y1, z1, x2, y2, z2;
if (anchor === 'box') {
    x1 = Math.min(cfg.x1, cfg.x2);
    x2 = Math.max(cfg.x1, cfg.x2);
    y1 = Math.min(cfg.y1, cfg.y2);
    y2 = Math.max(cfg.y1, cfg.y2);
    z1 = Math.min(cfg.z1, cfg.z2);
    z2 = Math.max(cfg.z1, cfg.z2);
} else if (anchor === 'under_player' || anchor === 'under_me' || anchor === 'below') {
    var cx = player.blockX;
    var cz = player.blockZ;
    var topY = player.blockY - 1;
    x1 = cx - Math.floor(width / 2);
    x2 = x1 + width - 1;
    z1 = cz - Math.floor(length / 2);
    z2 = z1 + length - 1;
    y2 = topY;
    y1 = topY - height + 1;
} else if (anchor === 'front_of_player' || anchor === 'tunnel') {
    var dx = Number(player.facingDx || 0);
    var dz = Number(player.facingDz || 1);
    if (dx === 0 && dz === 0) dz = 1;
    var baseX = player.blockX + dx;
    var baseZ = player.blockZ + dz;
    var sideX = dz;
    var sideZ = -dx;
    var half = Math.floor(width / 2);
    var minX = baseX, maxX = baseX, minZ = baseZ, maxZ = baseZ;
    for (var step = 0; step < length; step++) {
        var sx = baseX + dx * step;
        var sz = baseZ + dz * step;
        for (var w = -half; w <= half; w++) {
            var px = sx + sideX * w;
            var pz = sz + sideZ * w;
            if (step === 0 && w === 0) {
                minX = maxX = px;
                minZ = maxZ = pz;
            }
            if (px < minX) minX = px;
            if (px > maxX) maxX = px;
            if (pz < minZ) minZ = pz;
            if (pz > maxZ) maxZ = pz;
        }
    }
    x1 = minX;
    x2 = maxX;
    z1 = minZ;
    z2 = maxZ;
    y1 = player.blockY;
    y2 = player.blockY + height - 1;
} else {
    x1 = player.blockX - Math.floor(width / 2);
    x2 = x1 + width - 1;
    z1 = player.blockZ - Math.floor(length / 2);
    z2 = z1 + length - 1;
    y1 = player.blockY - Math.floor(height / 2);
    y2 = y1 + height - 1;
}

var box = drone.getBlocksInBox({x1: x1, y1: y1, z1: z1, x2: x2, y2: y2, z2: z2, includeAir: false});
if (!box || box.ok === false) {
    return {
        ok: false, error: 'could not read the box: ' + (box && box.error ? box.error : 'unknown'),
        box: {x1: x1, y1: y1, z1: z1, x2: x2, y2: y2, z2: z2}
    };
}

var targets = [];
var all = box.blocks || [];
for (var i = 0; i < all.length; i++) {
    var b = all[i];
    if (!b || b.isAir) continue;
    if (b.isLiquid && !includeLiquids) continue;
    if (filter !== 'all' && filter !== 'any' && !nameMatches(b.name, b.registryName, filter, b.oreDict)) continue;
    targets.push(b);
}

// Always work top-down so each cleared layer exposes the one below it.
targets.sort(function (a, b) {
    if (a.y !== b.y) return b.y - a.y;
    var da = Math.abs(a.x - player.blockX) + Math.abs(a.z - player.blockZ);
    var db = Math.abs(b.x - player.blockX) + Math.abs(b.z - player.blockZ);
    return da - db;
});

var mined = [];
var failed = 0;
var pickedUp = 0;
var planned = Math.min(targets.length, maxBlocks);
for (var t = 0; t < planned; t++) {
    var state = drone.state();
    if (state && state.slots === 0) {
        return {
            ok: false, mined: mined.length, failed: failed,
            remaining: targets.length - t, blocks: mined, stopped: 'no inventory slots',
            note: 'install an inventory upgrade on the drone before mining',
            box: {x1: x1, y1: y1, z1: z1, x2: x2, y2: y2, z2: z2}
        };
    }
    if (state && state.isFull === true) {
        return {
            ok: mined.length > 0, mined: mined.length, failed: failed,
            remaining: targets.length - t, blocks: mined, stopped: 'inventory full',
            box: {x1: x1, y1: y1, z1: z1, x2: x2, y2: y2, z2: z2}
        };
    }
    var target = targets[t];
    try {
        drone.goto({
            standoffOf: {x: target.x, y: target.y, z: target.z},
            allow_dig: cfg.allow_dig === true, max_wait_ms: 10000
        });
        var broke = drone.breakBlock({x: target.x, y: target.y, z: target.z, max_wait_ms: 10000});
        if (broke && broke.broken) {
            mined.push({x: target.x, y: target.y, z: target.z, name: target.name});
            if (cfg.collect !== false) {
                var loot = pickupNearbyLoot(SKILL_LIMITS.lootVacuumRadius, {walk: false});
                pickedUp += loot.pickedUp || 0;
            }
        } else failed++;
    } catch (breakError) {
        failed++;
        log('mine_box skip ' + target.x + ',' + target.y + ',' + target.z + ': ' + breakError.message);
    }
}

return {
    ok: mined.length > 0,
    anchor: anchor,
    filter: filter,
    mined: mined.length,
    failed: failed,
    pickedUp: pickedUp,
    remaining: Math.max(0, targets.length - mined.length),
    blocks: mined,
    box: {x1: x1, y1: y1, z1: z1, x2: x2, y2: y2, z2: z2},
    hint: mined.length ? '' : 'no reachable target in this volume; inspect the box or allow_dig'
};
