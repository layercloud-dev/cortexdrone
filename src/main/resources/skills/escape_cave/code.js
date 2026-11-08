// Vertical 2x2 escape shaft. params: { x, z, top_y, max_blocks, collect }
// Opens a two-wide shaft from the drone's feet to the surface so a carried
// player cannot suffocate in a one-block tunnel.
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var depth = cfg.max_blocks || 128;
var state = drone.state();
if (!state || state.ok === false) {
  return { ok: false, error: 'no live drone state' };
}

// The footprint is anchored on the caller's column when given (so a player
// standing there is inside it), otherwise on the drone's own column.
var anchorX = cfg.x !== undefined ? Math.round(cfg.x) : Math.floor(state.x);
var anchorZ = cfg.z !== undefined ? Math.round(cfg.z) : Math.floor(state.z);
var feet = Math.floor(state.y);

var top = cfg.top_y !== undefined ? Math.round(cfg.top_y) : null;
if (top === null) {
  var topRes = drone.getTopY({ x: anchorX, z: anchorZ });
  if (!topRes || topRes.ok === false) {
    return { ok: false, error: 'cannot read the surface height here: '
      + (topRes && topRes.error ? topRes.error : 'unknown'), x: anchorX, z: anchorZ };
  }
  top = topRes.topY;
}
// Two blocks above the highest solid block is open sky; never stop inside it.
var goalY = Math.max(top + 2, feet + 3);
if (goalY - feet > depth) {
  return { ok: false, error: 'surface is too far for one shaft',
    feet: feet, surfaceY: top, hint: 'call again with max_blocks raised or dig a staircase' };
}

// A 2x2 footprint is the point: a 1-wide shaft suffocates the passenger. The
// footprint starts at the anchor column and extends one block in +X and +Z.
var x0 = anchorX;
var z0 = anchorZ;
var cells = [[0, 0], [1, 0], [0, 1], [1, 1]];

var mined = 0;
var failed = 0;
var stopped = '';
var lastLayer = feet;
var y = feet;

function readCell(x, ty, z) {
  try {
    return drone.getBlock({ x: x, y: ty, z: z });
  } catch (readError) {
    return null;
  }
}

while (y < goalY) {
  // Clear two layers ahead (the rider needs feet + head) before moving up.
  for (var layer = y + 1; layer <= y + 2 && layer <= goalY + 1; layer++) {
    for (var c = 0; c < cells.length; c++) {
      var tx = x0 + cells[c][0];
      var tz = z0 + cells[c][1];
      var block = readCell(tx, layer, tz);
      if (!block) { failed++; continue; }
      if (block.isAir === true) continue;
      if (block.isLiquid === true) {
        stopped = 'liquid at y=' + layer + ' — refusing to open a flooded shaft';
        break;
      }
      if (Number(block.hardness) < 0) {
        stopped = 'unbreakable block at y=' + layer + ' (' + (block.name || 'unknown') + ')';
        break;
      }
      var broke = null;
      try {
        broke = drone.breakBlock({ x: tx, y: layer, z: tz, max_wait_ms: 15000 });
      } catch (breakError) {
        broke = null;
      }
      if (broke && broke.broken) mined++;
      else {
        failed++;
        // A container/machine cannot be tunnelled through; stop instead of
        // silently leaving a 1-wide hole the passenger would suffocate in.
        stopped = 'blocked by ' + (block.name || 'protected block') + ' at y=' + layer;
        break;
      }
    }
    if (stopped) break;
    // Falling gravel/sand can refill the layer we just cleared. Re-check and
    // clear once more before the drone rises into it.
    for (var r = 0; r < cells.length; r++) {
      var rx = x0 + cells[r][0];
      var rz = z0 + cells[r][1];
      var again = readCell(rx, layer, rz);
      if (again && again.isAir !== true && again.isLiquid !== true) {
        try {
          var rebroke = drone.breakBlock({ x: rx, y: layer, z: rz, max_wait_ms: 15000 });
          if (rebroke && rebroke.broken) mined++;
        } catch (retryError) {
          // Leave it: the next loop iteration will try again.
        }
      }
    }
  }
  if (stopped) break;

  lastLayer = y;
  var moved = null;
  try {
    moved = drone.goto({ x: x0 + 1.0, y: y + 1.35, z: z0 + 1.0,
      allow_dig: false, max_wait_ms: 20000, recovery_attempts: 6 });
  } catch (navError) {
    moved = { arrived: false, error: navError.message };
  }
  if (!moved || moved.arrived === false) {
    stopped = 'could not rise to y=' + (y + 1) + ': '
      + (moved && moved.error ? moved.error : 'navigation failed');
    break;
  }
  y++;
}

var collected = null;
if (cfg.collect !== false && mined > 0) {
  try {
    collected = skills.call('collect_drops', { radius: 8, filter: 'all', max_clusters: 4 });
  } catch (collectError) {
    log('escape_cave drop sweep skipped: ' + collectError.message);
  }
}

var open = !stopped && y >= goalY;
return {
  ok: open,
  x: x0,
  z: z0,
  fromY: feet,
  surfaceY: top,
  reachedY: y,
  goalY: goalY,
  mined: mined,
  failed: failed,
  stopped: stopped,
  onSurface: drone.state() ? drone.state().y >= top : false,
  collected: collected && collected.collected,
  hint: open
    ? '2x2 shaft is open to the sky; fly out through it'
    : 'shaft incomplete: do not fly through a 1-wide hole with a passenger'
};
