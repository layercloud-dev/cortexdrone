//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Drop junk from the drone inventory. params: { keep, also_drop, limit, count, side, dry_run }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var keepText = cfg.keep || 'ore,ingot,gem,crystal,dust,coal,lignite,clay,quartz,certus,malachite,copper,iron,gold,tin,lead,silver,nickel,uranium,thorium,plutonium,titanium,tungsten,aluminum,steel,diamond,emerald,lapis,redstone,food,meat,steak,bread,apple,carrot,potato';
var junkText = cfg.also_drop || 'cobblestone,dirt,gravel,flint,andesite,diorite,granite,sapling,seeds,stick,leaves,string,rotten flesh,bone,gunpowder,arrow,spider eye';
var keep = String(keepText).toLowerCase().split(/[,\s]+/);
var junk = String(junkText).toLowerCase().split(/[,\s]+/);
var limit = clamp(cfg.limit || SKILL_LIMITS.junkLimitDefault, 1, SKILL_LIMITS.junkLimitMax);
var count = cfg.count === undefined ? 64 : clamp(cfg.count, 0, SKILL_LIMITS.junkLimitMax);
var side = cfg.side === undefined ? LIMITS.sideDown : cfg.side;

function matchesAny(name, list) {
    var n = String(name || '').toLowerCase();
    for (var i = 0; i < list.length; i++) {
        if (list[i] && n.indexOf(list[i]) >= 0) return true;
    }
    return false;
}

var state = drone.state();
if (!state || state.ok === false) return {ok: false, error: 'no live drone state'};

var below = drone.getBlock({
    x: Math.floor(state.x),
    y: Math.floor(state.y) - 1,
    z: Math.floor(state.z)
});
if (below && below.isLiquid === true && String(below.name || '').toLowerCase().indexOf('lava') >= 0) {
    return {ok: false, error: 'lava directly below; move before dropping items'};
}

var victims = [];
var inv = state.inventory || [];
for (var i = 0; i < inv.length; i++) {
    var it = inv[i];
    if (!it || !it.count) continue;
    if (matchesAny(it.name, keep)) continue;
    if (!matchesAny(it.name, junk)) continue;
    victims.push(it);
    if (victims.length >= limit) break;
}

if (!victims.length) {
    return {ok: true, dropped: [], kept: state.totalItems || 0, freeSlots: state.freeSlots,
        hint: 'nothing matched the junk list'};
}
if (cfg.dry_run === true) {
    return {ok: true, dry_run: true, wouldDrop: victims.map(function (x) {
        return {name: x.name, count: x.count, ocSlot: x.ocSlot};
    }), hint: 'pass dry_run:false to drop'};
}

var dropped = [];
var failed = [];
for (var v = 0; v < victims.length; v++) {
    var stack = victims[v];
    var slot = stack.ocSlot || (stack.slot + 1);
    try {
        var selected = drone.ocCall({method: 'select', args: {1: slot}});
        if (selected && selected.ok === false) {
            failed.push({name: stack.name, error: selected.error});
            continue;
        }
        var moved = drone.ocCall({method: 'drop', args: {1: side, 2: count === 0 ? stack.count : count}});
        if (moved && moved.ok === false) {
            failed.push({name: stack.name, error: moved.error});
            continue;
        }
        dropped.push({name: stack.name, count: count === 0 ? stack.count : count});
    } catch (dropError) {
        failed.push({name: stack.name, error: dropError.message});
    }
}

var after = drone.state();
return {
    ok: dropped.length > 0,
    dropped: dropped,
    failed: failed,
    kept: after ? after.totalItems : 0,
    freeSlots: after ? after.freeSlots : 0,
    hint: failed.length ? 'some stacks could not be dropped; check the ocSlot mapping and side' : ''
};
