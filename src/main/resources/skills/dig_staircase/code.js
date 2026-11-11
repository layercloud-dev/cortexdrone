//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Safe descending staircase. params:
// { direction, length, start:{x,y,z}, headroom, width, max_blocks, allow_dig }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var direction = String(cfg.direction || 'north').toLowerCase();
var length = clamp(cfg.length || 16, 1, SKILL_LIMITS.smallCountCap);
var headroom = clamp(cfg.headroom || 2, 1, SKILL_LIMITS.shaftMax);
var width = clamp(cfg.width || 1, 1, SKILL_LIMITS.shaftMax);
var maxBlocks = clamp(cfg.max_blocks || SKILL_LIMITS.budget256, 1, LIMITS.maxDigBlocks);
var allowDig = cfg.allow_dig === true;

var axes = {
    north: {dx: 0, dz: -1}, south: {dx: 0, dz: 1},
    east: {dx: 1, dz: 0}, west: {dx: -1, dz: 0},
    down: {dx: 0, dz: 0}
};
var axis = axes[direction];
if (!axis) {
    return {
        ok: false, error: 'unknown direction "' + direction + '"',
        hint: 'use north, south, east, west or down'
    };
}
var vertical = direction === 'down';

var start = cfg.start;
if (!start) {
    var state = drone.state();
    start = {x: Math.round(state.x), y: Math.round(state.y) - 1, z: Math.round(state.z)};
    if (!vertical) {
        start.x += axis.dx * 2;
        start.z += axis.dz * 2;
    }
} else {
    start = {x: Math.round(start.x), y: Math.round(start.y), z: Math.round(start.z)};
}

// Dig order: each step cell (walking level) then its headroom, one step lower
// every time. The floor block under a step is never touched.
var plan = [];
for (var step = 0; step < length; step++) {
    var sy = start.y - step;
    if (sy < 3) break;
    if (vertical) {
        for (var ox = 0; ox < width; ox++) {
            for (var oz = 0; oz < width; oz++) {
                for (var h = 0; h < headroom; h++) {
                    plan.push({x: start.x + ox, y: sy + h, z: start.z + oz, step: step});
                }
            }
        }
        continue;
    }
    var sx = start.x + axis.dx * step;
    var sz = start.z + axis.dz * step;
    var half = Math.floor(width / 2);
    for (var w = -half; w <= half; w++) {
        var px = sx + (axis.dz !== 0 ? w : 0);
        var pz = sz + (axis.dx !== 0 ? w : 0);
        for (var h2 = 0; h2 < headroom; h2++) {
            plan.push({x: px, y: sy + h2, z: pz, step: step});
        }
    }
}

var mined = [];
var failed = 0;
var stopped = '';
for (var i = 0; i < plan.length && mined.length < maxBlocks; i++) {
    var target = plan[i];
    var now = drone.state();
    if (now && now.isFull === true) {
        stopped = 'inventory full';
        break;
    }
    try {
        var probe = drone.getBlock({x: target.x, y: target.y, z: target.z});
        if (probe && probe.isAir) continue;
        if (probe && (probe.isLiquid === true || Number(probe.hardness) < 0)) continue;
        var moved = drone.goto({
            standoffOf: {x: target.x, y: target.y, z: target.z},
            allow_dig: allowDig, max_wait_ms: 15000
        });
        if (!moved || moved.arrived === false) {
            failed++;
            continue;
        }
        var broke = drone.breakBlock({
            x: target.x, y: target.y, z: target.z,
            max_wait_ms: 15000
        });
        if (broke && broke.broken) mined.push({x: target.x, y: target.y, z: target.z});
        else failed++;
    } catch (digError) {
        failed++;
        log('stair skip ' + target.x + ',' + target.y + ',' + target.z + ': '
            + digError.message);
    }
}

var exit = plan.length ? {x: plan[0].x, y: plan[0].y, z: plan[0].z} : start;
if (mined.length) {
    var last = mined[mined.length - 1];
    exit = {x: last.x, y: last.y, z: last.z};
}

return {
    ok: mined.length > 0,
    direction: direction,
    steps: mined.length ? plan[plan.length > 0 ? Math.min(mined.length, plan.length) - 1 : 0].step + 1 : 0,
    requestedSteps: length,
    mined: mined.length,
    failed: failed,
    entry: plan.length ? {x: plan[0].x, y: plan[0].y, z: plan[0].z} : start,
    exit: exit,
    stopped: stopped,
    blocks: mined.length <= 40 ? mined : mined.slice(mined.length - 40),
    hint: mined.length ? (stopped ? stopped + '; unload and continue' : '')
        : 'nothing was cleared; check the start point is inside solid ground'
};
