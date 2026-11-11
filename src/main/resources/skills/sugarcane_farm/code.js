//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Sugar cane harvest. params: { radius, limit }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var radius = clamp(cfg.radius || LIMITS.scanRadiusDefault, LIMITS.blockScanRadiusMin, LIMITS.smallScanRadiusMax);
var limit = clamp(cfg.limit || SKILL_LIMITS.largeLimitDefault, 1, SKILL_LIMITS.largeScanLimit);

var found = drone.scanBlocks({filter: 'sugar cane', radius: radius});
if (!found || !found.count) {
    return {
        ok: false, error: 'no sugar cane within ' + radius + 'm',
        hint: 'sugar cane only grows on dirt/sand directly beside water'
    };
}

// Group blocks into columns so the bottom block is always kept.
var columns = {};
var list = found.blocks || [];
for (var i = 0; i < list.length; i++) {
    var block = list[i];
    var key = block.x + ',' + block.z;
    if (!columns[key]) columns[key] = [];
    columns[key].push(block);
}

var harvested = 0;
var columnsTouched = 0;
var skipped = 0;
var failed = 0;
var keys = Object.keys(columns);
for (var c = 0; c < keys.length && harvested < limit; c++) {
    var column = columns[keys[c]];
    column.sort(function (a, b) {
        return a.y - b.y;
    });

    // Skip every column with fewer than two blocks: a single block is the base
    // (immature cane), so leave it in the ground to grow. Never break column[0].
    if (column.length < 2) {
        skipped++;
        continue;
    }
    columnsTouched++;

    // Cut column[1], the first block above the base (the middle of a three-tall
    // cane). Sugar cane pops every block above the broken one in the same update,
    // so this single cut removes the whole upper column at once and keeps the
    // base for regrowth. Breaking from the top down needs one goto+break per
    // block and leaves stubs behind when a reach check fails.
    var cut = column[1];
    var expected = column.length - 1;
    try {
        drone.goto({standoffOf: {x: cut.x, y: cut.y, z: cut.z}, allow_dig: false});
        var broke = drone.breakBlock({x: cut.x, y: cut.y, z: cut.z});
        if (broke && broke.broken) harvested += expected;
        else failed += expected;
    } catch (breakError) {
        failed += expected;
        log('cane skip at ' + cut.x + ',' + cut.y + ',' + cut.z + ': ' + breakError.message);
    }
}

var collected = null;
try {
    collected = skills.call('collect_drops', {
        radius: Math.min(LIMITS.collectRadiusMax, radius), filter: 'sugar cane',
        max_clusters: SKILL_LIMITS.maxClusters
    });
} catch (collectError) {
    log('drop collection skipped: ' + collectError.message);
}

return {
    ok: harvested > 0,
    harvested: harvested,
    columns: columnsTouched,
    skipped: skipped,
    failed: failed,
    collected: collected && collected.collected,
    note: 'cut the first block above the base so the whole upper cane pops at once; the base stays and regrows'
};
