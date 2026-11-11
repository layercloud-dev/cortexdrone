//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Vertical shaft. params: { start, depth, target_y, size, headroom,
//   stop_at_bedrock, collect }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var state = drone.state();
if (!state || state.ok === false) {
    return {ok: false, error: 'no live drone state'};
}

var start = cfg.start
    ? {x: Math.round(cfg.start.x), y: Math.round(cfg.start.y), z: Math.round(cfg.start.z)}
    : {x: Math.round(state.x), y: Math.round(state.y) - 1, z: Math.round(state.z)};
var fromY = start.y;

var depth = clamp(cfg.depth || 20, 1, SKILL_LIMITS.largeScanLimit);
var bottomTarget = cfg.target_y !== undefined ? Math.round(cfg.target_y) : fromY - depth + 1;
if (bottomTarget > fromY) bottomTarget = fromY - depth + 1;
if (bottomTarget < 2) bottomTarget = 2;
depth = fromY - bottomTarget + 1;

var size = clamp(cfg.size || 1, 1, SKILL_LIMITS.shaftMax);
var headroom = clamp(cfg.headroom || 2, 1, SKILL_LIMITS.shaftMax);
var stopAtBedrock = cfg.stop_at_bedrock !== false;

var mined = 0;
var failed = 0;
var reachedBedrock = false;
var stopped = '';
var bottomY = fromY;

for (var layer = 0; layer < depth; layer++) {
    var y = fromY - layer;
    if (y < 2) {
        stopped = 'reached y=2';
        break;
    }
    bottomY = y;

    var now = drone.state();
    if (now && now.isFull === true) {
        stopped = 'inventory full';
        break;
    }

    // Refuse to open a liquid layer: a shaft into lava or water floods.
    var liquid = false;
    for (var ox = 0; ox < size && !liquid; ox++) {
        for (var oz = 0; oz < size && !liquid; oz++) {
            try {
                var probe = drone.getBlock({x: start.x + ox, y: y, z: start.z + oz});
                if (probe && probe.isLiquid === true) {
                    liquid = true;
                    stopped = 'liquid at y=' + y;
                }
            } catch (readError) {
            }
        }
    }
    if (liquid) break;

    for (var h = 0; h < headroom && !stopped; h++) {
        for (var cx = 0; cx < size && !stopped; cx++) {
            for (var cz = 0; cz < size && !stopped; cz++) {
                var tx = start.x + cx;
                var ty = y + h;
                var tz = start.z + cz;
                var target = null;
                try {
                    target = drone.getBlock({x: tx, y: ty, z: tz});
                } catch (readErr) {
                    failed++;
                    continue;
                }
                if (!target || target.isAir) continue;
                if (target.isLiquid === true) {
                    stopped = 'liquid at y=' + ty;
                    break;
                }
                if (Number(target.hardness) < 0) {
                    if (stopAtBedrock) {
                        reachedBedrock = true;
                        stopped = 'bedrock at y=' + ty;
                        break;
                    }
                    failed++;
                    continue;
                }
                try {
                    var moved = drone.goto({
                        standoffOf: {x: tx, y: ty, z: tz},
                        allow_dig: false, max_wait_ms: 12000
                    });
                    if (!moved || moved.arrived === false) {
                        failed++;
                        continue;
                    }
                    var broke = drone.breakBlock({x: tx, y: ty, z: tz, max_wait_ms: 12000});
                    if (broke && broke.broken) mined++;
                    else failed++;
                } catch (digError) {
                    failed++;
                    log('shaft skip ' + tx + ',' + ty + ',' + tz + ': ' + digError.message);
                }
            }
        }
    }
    if (stopped) break;
}

var collected = null;
if (cfg.collect !== false && mined > 0) {
    try {
        collected = skills.call('collect_drops', {
            radius: 10,
            filter: 'all',
            max_clusters: SKILL_LIMITS.maxClustersSmall
        });
    } catch (collectError) {
        log('shaft drop sweep skipped: ' + collectError.message);
    }
}

return {
    ok: mined > 0,
    depth: fromY - bottomY + 1,
    fromY: fromY,
    bottomY: bottomY,
    size: size,
    mined: mined,
    failed: failed,
    reachedBedrock: reachedBedrock,
    collected: collected && collected.collected,
    stopped: stopped,
    hint: mined > 0
        ? (stopped ? stopped : 'shaft open; add ladders on one wall for the player')
        : 'nothing was cleared; the column may already be open'
};
