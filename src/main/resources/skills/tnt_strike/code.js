//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Aerial TNT strike. params: { x, y, z, target, count, fuse_ticks, spread, collect, player }
// Doctrine: climb above the impact, check the release, drop fused charges, leave
// the blast radius before the fuse ends, then report the crater. The release
// itself is the tnt_drop tool: it owns the safety envelope and the inventory
// debit, so this skill never spawns an entity by hand.
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var count = clamp(cfg.count || SKILL_LIMITS.tntCountDefault,
    SKILL_LIMITS.tntCountMin, SKILL_LIMITS.tntCountMax);
var fuse = clamp(cfg.fuse_ticks || SKILL_LIMITS.tntFuseDefault,
    SKILL_LIMITS.tntFuseMin, SKILL_LIMITS.tntFuseMax);
var spread = clamp(cfg.spread || 0, 0, SKILL_LIMITS.tntSpreadMax);
var collect = cfg.collect !== false;

var state = drone.state();
if (!state || state.ok === false) return {ok: false, error: 'no live drone state'};

var impact = resolveImpact(cfg, state);
if (!impact) {
    return {ok: false, error: 'tnt_strike needs x/y/z or target:{x,y,z}',
        hint: 'pass the impact block, not the block above it'};
}

// A rider is never taken on a bombing run: the passenger would be inside the
// blast. Release them somewhere safe first.
if (state.ridingEntity || state.rider) {
    return {ok: false, error: 'a passenger is riding the drone',
        hint: 'land, unpick the passenger, then repeat the strike'};
}

var tntCount = countTntInInventory(state.inventory || []);
if (tntCount < count) {
    var player = cfg.player || ctx.player || ctx.owner;
    var taken = drone.takeFromPlayer({
        filter: 'tnt',
        count: Math.max(count, SKILL_LIMITS.tntRequestMin),
        player: player
    });
    state = drone.state();
    tntCount = countTntInInventory((state && state.inventory) || []);
    if (tntCount < count) {
        return {ok: false, error: 'the drone has no TNT and the player could not supply it',
            taken: taken, have: tntCount, want: count,
            hint: 'give the drone TNT first (sand + gunpowder crafts TNT)'};
    }
}

// Release point: above the impact, low enough that the charge does not drift.
var hoverY = impact.y + SKILL_LIMITS.tntSafeDistance;
var offTarget = Math.abs(safeNumber(state.x, 0) - (impact.x + 0.5)) > 1.0
    || Math.abs(safeNumber(state.z, 0) - (impact.z + 0.5)) > 1.0
    || safeNumber(state.y, 0) < impact.y + SKILL_LIMITS.tntSafeDistance;
if (offTarget) {
    var hovered = drone.goto({
        x: impact.x + 0.5,
        y: hoverY,
        z: impact.z + 0.5,
        allow_dig: false,
        max_wait_ms: LIMITS.maxWaitMs
    });
    if (hovered && hovered.ok === false && hovered.needsMovement !== true) {
        return {ok: false, error: 'could not reach the release point',
            impact: impact, navigation: hovered};
    }
}

var dropped = drone.tntDrop({
    x: impact.x,
    y: impact.y,
    z: impact.z,
    count: count,
    fuse_ticks: fuse,
    spread: spread
});
if (dropped && (dropped.ok === false || dropped.needsMovement === true)) {
    // One retry after the standoff the tool named; a second refusal is real.
    var move = dropped.standoff || dropped.hover;
    if (move) {
        drone.goto({x: move.x, y: move.y, z: move.z, allow_dig: false,
            max_wait_ms: LIMITS.maxWaitMs});
        dropped = drone.tntDrop({x: impact.x, y: impact.y, z: impact.z,
            count: count, fuse_ticks: fuse, spread: spread});
    }
    if (!dropped || dropped.ok === false) {
        return {ok: false, error: 'TNT release refused', impact: impact, drop: dropped};
    }
}

// Leave. The tool reports the blast radius and an escape point; the fuse is
// already burning, so nothing may wait before this goto.
var escape = dropped.escape_to || {
    x: impact.x + SKILL_LIMITS.tntSafeDistance + SKILL_LIMITS.tntEscapeMargin,
    y: hoverY,
    z: impact.z + SKILL_LIMITS.tntSafeDistance + SKILL_LIMITS.tntEscapeMargin
};
var away = drone.goto({x: escape.x, y: escape.y, z: escape.z, allow_dig: false,
    max_wait_ms: LIMITS.maxWaitMs});
var waitMs = Math.round((Number(dropped.detonation_in_seconds || (fuse / TICKS_PER_SECOND))
    + 1) * 1000);
sleep(Math.min(SKILL_LIMITS.sleepCapMs, Math.max(0, waitMs)));

var collected = 0;
if (collect) {
    var swept = drone.suckItems({radius: SKILL_LIMITS.lootVacuumRadius, filter: 'valuable'});
    collected = swept && swept.pickedUp ? Number(swept.pickedUp) : 0;
}
var after = drone.state();
return {
    ok: true,
    dropped: dropped.dropped || count,
    impact: impact,
    escape: {x: escape.x, y: escape.y, z: escape.z},
    escapeOk: !away || away.ok !== false,
    fuse_ticks: dropped.fuse_ticks || fuse,
    blast_radius: dropped.blast_radius || SKILL_LIMITS.tntBlastRadius,
    collected: collected,
    remaining_tnt: dropped.remaining_tnt !== undefined ? dropped.remaining_tnt
        : countTntInInventory((after && after.inventory) || []),
    position: after ? {x: after.x, y: after.y, z: after.z} : null,
    hint: 'the crater is at the impact block; scan it with get_blocks_in_box before rebuilding'
};

function resolveImpact(conf, state) {
    if (conf.target && typeof conf.target === 'object') {
        return floorPoint(conf.target.x, conf.target.y, conf.target.z);
    }
    if (conf.x === undefined || conf.z === undefined) return null;
    if (conf.y !== undefined) return floorPoint(conf.x, conf.y, conf.z);
    var top = drone.getTopY({x: Math.floor(conf.x), z: Math.floor(conf.z)});
    var groundY = top && top.topY !== undefined ? top.topY : Math.floor(safeNumber(state.y, 0)) - 1;
    return floorPoint(conf.x, groundY, conf.z);
}

function floorPoint(x, y, z) {
    if (x === undefined || y === undefined || z === undefined) return null;
    var point = {x: Math.floor(x), y: Math.floor(y), z: Math.floor(z)};
    return (isNaN(point.x) || isNaN(point.y) || isNaN(point.z)) ? null : point;
}

function safeNumber(value, fallback) {
    var number = Number(value);
    return isNaN(number) ? fallback : number;
}

function countTntInInventory(inv) {
    var total = 0;
    for (var i = 0; i < inv.length; i++) {
        var it = inv[i];
        if (!it || !it.count) continue;
        var n = String(it.name || '').toLowerCase();
        if (n.indexOf('tnt') >= 0 || n.indexOf('dynamite') >= 0) total += Number(it.count);
    }
    return total;
}
