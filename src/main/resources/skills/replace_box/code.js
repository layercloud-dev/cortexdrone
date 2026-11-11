//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Replace every matching block in a 3D box atomically. params:
// { x1,y1,z1,x2,y2,z2, block, only_replace, meta, max_blocks, dry_run }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var block = cfg.block || cfg.with || null;
if (!block) {
    return {ok: false, error: 'replace_box needs a target block name'};
}
if (cfg.x1 === undefined || cfg.y1 === undefined || cfg.z1 === undefined
    || cfg.x2 === undefined || cfg.y2 === undefined || cfg.z2 === undefined) {
    return {ok: false, error: 'replace_box needs x1,y1,z1 and x2,y2,z2'};
}

var x1 = Math.min(cfg.x1, cfg.x2), x2 = Math.max(cfg.x1, cfg.x2);
var y1 = Math.min(cfg.y1, cfg.y2), y2 = Math.max(cfg.y1, cfg.y2);
var z1 = Math.min(cfg.z1, cfg.z2), z2 = Math.max(cfg.z1, cfg.z2);
var onlyReplace = cfg.only_replace || 'all';
var skipLiquids = cfg.include_liquids !== true;
var meta = cfg.meta === undefined ? -1 : cfg.meta;
var maxBlocks = clamp(cfg.max_blocks || 256, 1, SKILL_LIMITS.budget1200);

var box = drone.getBlocksInBox({x1: x1, y1: y1, z1: z1, x2: x2, y2: y2, z2: z2, includeAir: false});
if (!box || box.ok === false) {
    return {ok: false, error: 'could not read the box: ' + (box && box.error ? box.error : 'unknown')};
}

var candidates = [];
var blocks = box.blocks || [];
for (var i = 0; i < blocks.length; i++) {
    var b = blocks[i];
    if (!b || b.isAir) continue;
    if (b.isLiquid && skipLiquids) continue;
    if (onlyReplace !== 'all' && onlyReplace !== 'any'
        && !nameMatches(b.name, b.registryName, onlyReplace, b.oreDict)) continue;
    if (nameMatches(b.name, b.registryName, block, b.oreDict) && (meta < 0 || b.meta === meta)) continue;
    candidates.push(b);
}

candidates.sort(function (a, b) {
    if (a.y !== b.y) return b.y - a.y;
    return (Math.abs(a.x) + Math.abs(a.z)) - (Math.abs(b.x) + Math.abs(b.z));
});

if (cfg.dry_run === true) {
    return {
        ok: true, dryRun: true, block: block, onlyReplace: onlyReplace,
        totalVoxels: box.totalVoxels, nonAir: box.nonAirCount,
        candidates: candidates.length, countsByType: box.countsByType,
        hint: 're-run with dry_run:false to perform the replacements'
    };
}

var held = countInInventory(drone.state().inventory, block);
if (held <= 0) {
    return {
        ok: false, error: 'no "' + block + '" in the drone inventory',
        needed: candidates.length, hint: 'gather or withdraw the block first; nothing was changed'
    };
}

var replaced = 0;
var already = 0;
var failed = 0;
var missing = 0;
var planned = Math.min(candidates.length, maxBlocks);
var lastError = '';

for (var t = 0; t < planned; t++) {
    var target = candidates[t];
    try {
        var result = drone.replaceBlock({x: target.x, y: target.y, z: target.z, block: block, meta: meta});
        if (result && result.replaced) {
            if (result.alreadyMatching) already++;
            else replaced++;
        } else {
            failed++;
        }
    } catch (replaceError) {
        lastError = String(replaceError.message || '');
        if (lastError.indexOf('No ') === 0 || lastError.indexOf('inventory') >= 0) {
            missing = candidates.length - t;
            break;
        }
        failed++;
    }
}

return {
    ok: replaced > 0 || already > 0,
    block: block,
    onlyReplace: onlyReplace,
    replaced: replaced,
    alreadyMatching: already,
    failed: failed,
    missingRemaining: missing,
    planned: planned,
    candidates: candidates.length,
    remaining: Math.max(0, candidates.length - replaced - already),
    lastError: lastError,
    bounds: {x1: x1, y1: y1, z1: z1, x2: x2, y2: y2, z2: z2},
    inventoryLeft: countInInventory(drone.state().inventory, block)
};
