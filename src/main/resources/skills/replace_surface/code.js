// Replace a room surface atomically. params:
// { surface, block, only_replace, meta, max_blocks, dry_run, x, y, z, max_radius }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var surface = String(cfg.surface || 'floor').toLowerCase();
var block = cfg.block || cfg.with || null;
var onlyReplace = cfg.only_replace || 'all';
var meta = cfg.meta === undefined ? -1 : cfg.meta;
var maxBlocks = clamp(cfg.max_blocks || 256, 1, 1200);
var dryRun = cfg.dry_run === true;

if (!block) {
  return { ok: false, error: 'replace_surface needs a target block name' };
}

var roomRequest = { max_radius: cfg.max_radius || 18 };
if (cfg.x !== undefined && cfg.y !== undefined && cfg.z !== undefined) {
  roomRequest.x = cfg.x;
  roomRequest.y = cfg.y;
  roomRequest.z = cfg.z;
}
var room = drone.scanRoom(roomRequest);
if (!room || room.ok === false) {
  return { ok: false, error: 'room scan failed: ' + (room && room.error ? room.error : 'unknown') };
}

var groups = [];
function addGroup(name) {
  if (room[name] && room[name].length) groups.push({ name: name, points: room[name] });
}
if (surface === 'all') {
  addGroup('floor');
  addGroup('walls');
  addGroup('ceiling');
} else if (surface === 'walls') {
  addGroup('walls');
} else if (surface === 'north_wall') {
  addGroup('northWall');
} else if (surface === 'south_wall') {
  addGroup('southWall');
} else if (surface === 'east_wall') {
  addGroup('eastWall');
} else if (surface === 'west_wall') {
  addGroup('westWall');
} else if (surface === 'ceiling') {
  addGroup('ceiling');
} else {
  addGroup('floor');
}

var points = [];
for (var g = 0; g < groups.length; g++) {
  var group = groups[g];
  for (var i = 0; i < group.points.length; i++) {
    var p = group.points[i];
    points.push({ x: p[0], y: p[1], z: p[2], surface: group.name });
  }
}

// Ceilings/walls top-down; floors bottom-up. Atomic replacement keeps support intact.
var floorFirst = surface === 'floor';
points.sort(function (a, b) {
  if (a.surface !== b.surface) return a.surface < b.surface ? -1 : 1;
  var da = Math.abs(a.x) + Math.abs(a.z);
  var db = Math.abs(b.x) + Math.abs(b.z);
  if (floorFirst && a.y !== b.y) return a.y - b.y;
  if (!floorFirst && a.y !== b.y) return b.y - a.y;
  return da - db;
});

if (dryRun) {
  var counts = {};
  for (var c = 0; c < points.length; c++) {
    counts[points[c].surface] = (counts[points[c].surface] || 0) + 1;
  }
  return { ok: true, dryRun: true, surface: surface, block: block,
    total: points.length, bySurface: counts,
    bounds: room.bounds, materials: room[surface === 'all' ? 'wallMaterials' : 'floorMaterials'] || null,
    hint: 're-run with dry_run:false to perform the replacements' };
}

var held = countInInventory(drone.state().inventory, block);
if (held <= 0) {
  return { ok: false, error: 'no "' + block + '" in the drone inventory', needed: points.length,
    hint: 'gather or withdraw the block first; nothing was changed' };
}

var replaced = 0;
var already = 0;
var skipped = 0;
var failed = 0;
var missing = 0;
var planned = Math.min(points.length, maxBlocks);
var lastError = '';

for (var t = 0; t < planned; t++) {
  var target = points[t];
  var current = null;
  try {
    current = drone.getBlock({ x: target.x, y: target.y, z: target.z });
  } catch (readError) {
    failed++;
    continue;
  }
  if (!current || current.isAir) { skipped++; continue; }
  if (onlyReplace !== 'all' && onlyReplace !== 'any'
      && !nameMatches(current.name, current.registryName, onlyReplace, current.oreDict)) {
    skipped++;
    continue;
  }
  if (nameMatches(current.name, current.registryName, block, current.oreDict)
      && (meta < 0 || current.meta === meta)) {
    already++;
    continue;
  }
  try {
    var result = drone.replaceBlock({ x: target.x, y: target.y, z: target.z, block: block, meta: meta });
    if (result && result.replaced) {
      if (result.alreadyMatching) already++;
      else replaced++;
    } else {
      failed++;
    }
  } catch (replaceError) {
    lastError = String(replaceError.message || '');
    if (lastError.indexOf('No ') === 0 || lastError.indexOf('inventory') >= 0) {
      missing = points.length - t;
      break;
    }
    failed++;
    log('replace failed at ' + target.x + ',' + target.y + ',' + target.z + ': ' + lastError);
  }
}

return {
  ok: replaced > 0 || already > 0,
  surface: surface,
  block: block,
  replaced: replaced,
  alreadyMatching: already,
  skipped: skipped,
  failed: failed,
  missingRemaining: missing,
  planned: planned,
  total: points.length,
  remaining: Math.max(0, points.length - replaced - already - skipped),
  lastError: lastError,
  bounds: room.bounds,
  inventoryLeft: countInInventory(drone.state().inventory, block)
};
