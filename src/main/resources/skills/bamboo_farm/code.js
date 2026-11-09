// Bamboo harvest. params: { radius, limit }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var radius = clamp(cfg.radius || LIMITS.scanRadiusDefault, LIMITS.blockScanRadiusMin, LIMITS.smallScanRadiusMax);
var limit = clamp(cfg.limit || 64, 1, SKILL_LIMITS.scanLimit192);

var found = drone.scanBlocks({ filter: 'bamboo', radius: radius });
if (!found || !found.count) {
  return { ok: false, error: 'no bamboo within ' + radius + 'm',
    hint: 'bamboo grows from a bamboo shoot on dirt/sand/grass; plant one or widen the radius' };
}

// Group stalks into columns and skip shoots/saplings so they can grow.
var columns = {};
var saplings = 0;
var list = found.blocks || [];
for (var i = 0; i < list.length; i++) {
  var block = list[i];
  var reg = String(block.registryName || '').toLowerCase();
  var name = String(block.name || '').toLowerCase();
  if (reg.indexOf('sapling') >= 0 || reg.indexOf('shoot') >= 0
      || name.indexOf('sapling') >= 0 || name.indexOf('shoot') >= 0) {
    saplings++;
    continue;
  }
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
  column.sort(function (a, b) { return a.y - b.y; });

  // Skip every column with fewer than two blocks: a single block is the base
  // (young stalk), so leave it in the ground to grow. Never break column[0].
  if (column.length < 2) {
    skipped++;
    continue;
  }
  columnsTouched++;

  // Cut column[1], the first block above the base. Bamboo pops every block
  // above the broken one in the same update, so one break removes the whole
  // stalk above the cut and keeps the base for regrowth.
  var cut = column[1];
  var expected = column.length - 1;
  try {
    drone.goto({ standoffOf: { x: cut.x, y: cut.y, z: cut.z }, allow_dig: false });
    var broke = drone.breakBlock({ x: cut.x, y: cut.y, z: cut.z });
    if (broke && broke.broken) harvested += expected;
    else failed += expected;
  } catch (breakError) {
    failed += expected;
    log('bamboo skip at ' + cut.x + ',' + cut.y + ',' + cut.z + ': ' + breakError.message);
  }
}

var collected = null;
try {
  collected = skills.call('collect_drops', { radius: Math.min(LIMITS.collectRadiusMax, radius), filter: 'bamboo',
    max_clusters: SKILL_LIMITS.maxClusters });
} catch (collectError) {
  log('drop collection skipped: ' + collectError.message);
}

return {
  ok: harvested > 0,
  harvested: harvested,
  columns: columnsTouched,
  skipped: skipped,
  saplings: saplings,
  failed: failed,
  collected: collected && collected.collected,
  note: 'cut the first block above the base so the whole upper stalk pops at once; the base stays and regrows'
};
