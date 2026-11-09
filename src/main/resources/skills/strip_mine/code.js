// Strip / branch mining. params:
// { direction, length, height, branches, branch_spacing, branch_length,
//   branch_side, start, max_blocks, ore_scan, collect }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var direction = String(cfg.direction || 'north').toLowerCase();
var length = clamp(cfg.length || 32, 1, SKILL_LIMITS.smallScanLimit);
var height = clamp(cfg.height || 2, 1, SKILL_LIMITS.shaftMax);
var branches = clamp(cfg.branches || 4, 0, SKILL_LIMITS.smallMax16);
var spacing = clamp(cfg.branch_spacing || 8, SKILL_LIMITS.branchSpacingMin, SKILL_LIMITS.branchSpacingMax);
var branchLength = clamp(cfg.branch_length || 12, 1, SKILL_LIMITS.branchLengthMax);
var side = String(cfg.branch_side || 'left').toLowerCase();
var maxBlocks = clamp(cfg.max_blocks || 512, 1, SKILL_LIMITS.budget1024);
var collect = cfg.collect !== false;

var axes = {
  north: { dx: 0, dz: -1 }, south: { dx: 0, dz: 1 },
  east: { dx: 1, dz: 0 }, west: { dx: -1, dz: 0 }
};
var axis = axes[direction];
if (!axis) {
  return { ok: false, error: 'unknown direction "' + direction + '"',
    hint: 'use north, south, east or west; dig_staircase handles down' };
}

var start = cfg.start;
if (!start) {
  var state = drone.state();
  start = { x: Math.round(state.x), y: Math.round(state.y) - 1, z: Math.round(state.z) };
}
start = { x: Math.round(start.x), y: Math.round(start.y), z: Math.round(start.z) };

// Dig order: main tunnel, with each branch expanded at its junction so the
// drone never has to fly through unmined rock.
var plan = [];
var branchCount = 0;
var perp = { dx: -axis.dz, dz: axis.dx };
for (var step = 0; step < length; step++) {
  var cx = start.x + axis.dx * step;
  var cz = start.z + axis.dz * step;
  for (var h = 0; h < height; h++) {
    plan.push({ x: cx, y: start.y + h, z: cz });
  }
  if (branches > 0 && step > 0 && step % spacing === 0 && branchCount < branches) {
    var signs = [];
    if (side === 'both') { signs.push(1); signs.push(-1); }
    else if (side === 'right') signs.push(-1);
    else signs.push(1);
    for (var s = 0; s < signs.length && branchCount < branches; s++) {
      var sign = signs[s];
      for (var b = 1; b <= branchLength; b++) {
        var bx = cx + perp.dx * sign * b;
        var bz = cz + perp.dz * sign * b;
        for (var bh = 0; bh < height; bh++) {
          plan.push({ x: bx, y: start.y + bh, z: bz });
        }
      }
      branchCount++;
    }
  }
}

var mined = 0;
var failed = 0;
var stopped = '';
var planned = Math.min(plan.length, maxBlocks);
for (var i = 0; i < planned; i++) {
  var target = plan[i];
  if (i % 8 === 0) {
    var now = drone.state();
    if (now && now.isFull === true) { stopped = 'inventory full'; break; }
  }
  try {
    var probe = drone.getBlock({ x: target.x, y: target.y, z: target.z });
    if (probe && (probe.isAir || probe.isLiquid === true || Number(probe.hardness) < 0)) continue;
    // standoffOf resolves a reachable hover point next to the block, so the
    // drone never needs an already-mined cell to fly into.
    var moved = drone.goto({ standoffOf: { x: target.x, y: target.y, z: target.z },
      allow_dig: false, max_wait_ms: 12000 });
    if (!moved || moved.arrived === false) { failed++; continue; }
    var broke = drone.breakBlock({ x: target.x, y: target.y, z: target.z,
      max_wait_ms: 12000 });
    if (broke && broke.broken) mined++;
    else failed++;
  } catch (digError) {
    failed++;
    log('strip skip ' + target.x + ',' + target.y + ',' + target.z + ': '
      + digError.message);
  }
}

var last = plan.length ? plan[Math.min(planned, plan.length) - 1] : start;
var exit = { x: last.x, y: last.y, z: last.z };

var ore = null;
if (cfg.ore_scan !== false) {
  try {
    var veins = drone.scanVeins({ filter: 'ores', radius: 16,
      x: Math.round((start.x + exit.x) / 2),
      y: Math.round((start.y + exit.y) / 2),
      z: Math.round((start.z + exit.z) / 2) });
    ore = { count: (veins && veins.veins) ? veins.veins.length : 0,
      veins: veins && veins.veins ? veins.veins.slice(0, 8) : [] };
  } catch (veinError) {
    ore = { error: veinError.message };
  }
}

var collected = null;
if (collect) {
  try {
    collected = skills.call('collect_drops', { radius: 10, filter: 'all', max_clusters: SKILL_LIMITS.maxClustersSmall });
  } catch (collectError) {
    log('drop sweep skipped: ' + collectError.message);
  }
}

return {
  ok: mined > 0,
  direction: direction,
  mined: mined,
  failed: failed,
  branches: branchCount,
  length: length,
  planned: planned,
  start: start,
  exit: exit,
  ore: ore,
  collected: collected && collected.collected,
  stopped: stopped,
  hint: mined > 0 ? (stopped ? stopped + '; unload then continue' : '')
    : 'nothing was cleared; the start point may already be open or blocked'
};
