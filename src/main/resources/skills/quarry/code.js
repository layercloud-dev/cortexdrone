// Layered rectangular quarry. params:
// { x1,z1,x2,z2 | width,length, top_y, depth|layers, filter, max_blocks,
//   collect_every, allow_liquid }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var state = drone.state();
if (!state || state.ok === false) {
  return { ok: false, error: 'no live drone state' };
}

var x1, z1, x2, z2;
if (cfg.x1 !== undefined && cfg.z1 !== undefined && cfg.x2 !== undefined && cfg.z2 !== undefined) {
  x1 = Math.min(cfg.x1, cfg.x2); x2 = Math.max(cfg.x1, cfg.x2);
  z1 = Math.min(cfg.z1, cfg.z2); z2 = Math.max(cfg.z1, cfg.z2);
} else {
  var width = clamp(cfg.width || 5, 1, 33);
  var length = clamp(cfg.length || width, 1, 33);
  var cx = Math.round(state.x);
  var cz = Math.round(state.z);
  x1 = cx - Math.floor(width / 2);
  x2 = x1 + width - 1;
  z1 = cz - Math.floor(length / 2);
  z2 = z1 + length - 1;
}

var topY = cfg.top_y !== undefined ? Math.round(cfg.top_y) : Math.round(state.y) - 1;
var layers = clamp(cfg.depth || cfg.layers || 3, 1, 32);
var filter = cfg.filter || 'all';
var maxBlocks = clamp(cfg.max_blocks || 512, 1, 1024);
var collectEvery = clamp(cfg.collect_every === undefined ? 3 : cfg.collect_every, 0, 16);
var allowLiquid = cfg.allow_liquid === true;

var mined = 0;
var skipped = 0;
var failed = 0;
var layersDone = 0;
var stopped = '';
var layerY = topY;

for (var layer = 0; layer < layers; layer++) {
  layerY = topY - layer;
  if (layerY < 2) { stopped = 'reached y=2'; break; }

  // Nearest-first within a layer so the drone never mines beneath itself.
  var cells = [];
  for (var x = x1; x <= x2; x++) {
    for (var z = z1; z <= z2; z++) {
      var dx = x - state.x;
      var dz = z - state.z;
      cells.push({ x: x, z: z, d: dx * dx + dz * dz });
    }
  }
  cells.sort(function (a, b) { return a.d - b.d; });

  for (var i = 0; i < cells.length; i++) {
    if (mined >= maxBlocks) { stopped = 'max_blocks'; break; }
    if (i % 8 === 0) {
      var now = drone.state();
      if (now && now.isFull === true) { stopped = 'inventory full'; break; }
    }
    var cell = cells[i];
    var probe;
    try {
      probe = drone.getBlock({ x: cell.x, y: layerY, z: cell.z });
    } catch (readError) {
      skipped++;
      continue;
    }
    if (!probe || probe.isAir) continue;
    if (probe.isLiquid === true && !allowLiquid) { skipped++; continue; }
    if (Number(probe.hardness) < 0) { skipped++; continue; }
    if (filter !== 'all' && filter !== 'any'
        && !nameMatches(probe.name, probe.registryName, filter)) { skipped++; continue; }

    try {
      var moved = drone.goto({ standoffOf: { x: cell.x, y: layerY, z: cell.z },
        allow_dig: false, max_wait_ms: 12000 });
      if (!moved || moved.arrived === false) { failed++; continue; }
      var broke = drone.breakBlock({ x: cell.x, y: layerY, z: cell.z,
        max_wait_ms: 12000 });
      if (broke && broke.broken) mined++;
      else failed++;
    } catch (digError) {
      failed++;
      log('quarry skip ' + cell.x + ',' + layerY + ',' + cell.z + ': ' + digError.message);
    }
  }
  layersDone = layer + 1;

  if (collectEvery > 0 && (layer + 1) % collectEvery === 0) {
    try {
      skills.call('collect_drops', { radius: 12, filter: 'all', max_clusters: 8 });
    } catch (collectError) {
      log('quarry drop sweep skipped: ' + collectError.message);
    }
  }
  if (stopped) break;
}

return {
  ok: mined > 0,
  layers: layersDone,
  requestedLayers: layers,
  mined: mined,
  skipped: skipped,
  failed: failed,
  layerY: layerY,
  pit: { x1: x1, z1: z1, x2: x2, z2: z2, topY: topY },
  stopped: stopped,
  hint: mined > 0
    ? (stopped ? stopped + ': unload and re-run with top_y=' + (layerY - 1) : '')
    : 'nothing was cleared; check the footprint and top_y'
};
