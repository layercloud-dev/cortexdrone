// Sugar cane harvest. params: { radius, limit, plant_new }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var radius = clamp(cfg.radius || 24, 4, 32);
var limit = clamp(cfg.limit || 48, 1, 128);

var found = drone.scanBlocks({ filter: 'sugar cane', radius: radius });
if (!found || !found.count) {
  return { ok: false, error: 'no sugar cane within ' + radius + 'm',
    hint: 'sugar cane only grows on dirt/sand directly beside water' };
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
var failed = 0;
var keys = Object.keys(columns);
for (var c = 0; c < keys.length && harvested < limit; c++) {
  var column = columns[keys[c]];
  column.sort(function (a, b) { return a.y - b.y; });
  if (column.length < 2) continue; // one block tall: leave it to grow
  columnsTouched++;
  // Break from the top down so the upper blocks do not fall into the water.
  for (var k = column.length - 1; k >= 1 && harvested < limit; k--) {
    var cane = column[k];
    try {
      drone.goto({ standoffOf: { x: cane.x, y: cane.y, z: cane.z }, allow_dig: false });
      var broke = drone.breakBlock({ x: cane.x, y: cane.y, z: cane.z });
      if (broke && broke.broken) harvested++;
      else failed++;
    } catch (breakError) {
      failed++;
      log('cane skip at ' + cane.x + ',' + cane.y + ',' + cane.z + ': ' + breakError.message);
    }
  }
}

var collected = null;
try {
  collected = skills.call('collect_drops', { radius: Math.min(16, radius), filter: 'sugar cane',
    max_clusters: 10 });
} catch (collectError) {
  log('drop collection skipped: ' + collectError.message);
}

return {
  ok: harvested > 0,
  harvested: harvested,
  columns: columnsTouched,
  failed: failed,
  collected: collected && collected.collected,
  note: 'the bottom block stays and regrows on its own; no replanting needed'
};
