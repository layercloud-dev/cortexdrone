// Cactus column harvest. params: { radius, limit, keep, point }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var radius = clamp(cfg.radius || 24, 4, 32);
var limit = clamp(cfg.limit || 24, 1, 64);
var keep = clamp(cfg.keep || 1, 1, 3);

var request = { filter: 'cactus', radius: radius, limit: 800 };
if (cfg.point) {
  request.x = Math.round(cfg.point.x);
  request.y = Math.round(cfg.point.y);
  request.z = Math.round(cfg.point.z);
}
var found = drone.scanBlocks(request);
if (!found || !found.count) {
  return { ok: false, error: 'no cactus within ' + radius + 'm',
    hint: 'cactus grows in deserts on sand; widen the radius or fly there' };
}

// Group blocks into vertical columns keyed by x/z.
var columns = {};
var blocks = found.blocks || [];
for (var i = 0; i < blocks.length; i++) {
  var b = blocks[i];
  var key = b.x + ',' + b.z;
  if (!columns[key]) columns[key] = [];
  columns[key].push(b);
}

var keys = Object.keys(columns);
var harvested = 0;
var columnsCut = 0;
var failed = 0;
for (var k = 0; k < keys.length && columnsCut < limit; k++) {
  var column = columns[keys[k]];
  column.sort(function (a, b) { return a.y - b.y; }); // bottom to top
  if (column.length <= keep) continue;

  var baseY = column[0].y;
  var cutIndex = keep; // first block above the kept base(s)
  var cut = column[cutIndex];
  if (!cut) continue;

  var state = drone.state();
  if (state && state.isFull === true) {
    return { ok: harvested > 0, harvested: harvested, columns: columnsCut,
      failed: failed, stopped: 'inventory full', cactusLeft: state.totalItems };
  }

  var before = countInInventory(state.inventory, 'cactus');
  try {
    // The bottom block is kept; cutting above it pops the rest of the column.
    drone.goto({ standoffOf: { x: cut.x, y: cut.y, z: cut.z }, allow_dig: false });
    var broke = drone.breakBlock({ x: cut.x, y: cut.y, z: cut.z });
    if (!broke || !broke.broken) { failed++; continue; }
  } catch (breakError) {
    failed++;
    log('cactus skip at ' + cut.x + ',' + cut.y + ',' + cut.z + ': ' + breakError.message);
    continue;
  }

  var after = countInInventory(drone.state().inventory, 'cactus');
  harvested += Math.max(1, after - before);
  columnsCut++;

  // Top up any stubs the pop left behind (should be rare, keeps columns clean).
  try {
    var top = drone.getBlock({ x: cut.x, y: baseY + keep, z: cut.z });
    if (top && nameMatches(top.name, top.registryName, 'cactus', top.oreDict)) {
      drone.breakBlock({ x: cut.x, y: baseY + keep, z: cut.z });
    }
  } catch (stubError) {
    log('cactus stub check skipped: ' + stubError.message);
  }
}

var collected = null;
try {
  collected = skills.call('collect_drops', { radius: Math.min(16, radius),
    filter: 'cactus', max_clusters: 12 });
} catch (collectError) {
  log('drop collection skipped: ' + collectError.message);
}

return {
  ok: harvested > 0,
  harvested: harvested,
  columns: columnsCut,
  failed: failed,
  keep: keep,
  cactusLeft: countInInventory(drone.state().inventory, 'cactus'),
  collected: collected && collected.collected,
  hint: harvested > 0 ? ''
    : 'no column higher than ' + keep + ' block(s); cactus grows to three, come back later'
};
