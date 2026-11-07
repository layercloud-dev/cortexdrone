// Standard 4x5 nether portal. params:
// { x,z,y, axis, clear, ignite, dry_run }
var cfg = (typeof params === 'object' && params !== null) ? params : {};

// Frame geometry: 4 wide (2 interior) by 5 tall (3 interior);
// 14 obsidian blocks total.
var frameWidth = 4;
var frameHeight = 5;

var player = drone.getPlayer({});
if (!player && cfg.x === undefined) {
  return { ok: false, error: 'player is offline; pass x/z explicitly' };
}

var axis = String(cfg.axis || '').toLowerCase();
if (axis !== 'x' && axis !== 'z') {
  // Run the frame across the player's view so the opening faces them.
  var fdx = Number(player && player.facingDx !== undefined ? player.facingDx : 0);
  var fdz = Number(player && player.facingDz !== undefined ? player.facingDz : 1);
  axis = Math.abs(fdz) >= Math.abs(fdx) ? 'x' : 'z';
}

var centerX, centerZ;
if (cfg.x !== undefined && cfg.z !== undefined) {
  centerX = Math.round(cfg.x);
  centerZ = Math.round(cfg.z);
} else {
  centerX = player.blockX + Number(player.facingDx || 0) * 4;
  centerZ = player.blockZ + Number(player.facingDz || 1) * 4;
}

var floorY = cfg.y !== undefined ? Math.round(cfg.y) : null;
if (floorY === null) {
  var top = drone.getTopY({ x: centerX, z: centerZ });
  floorY = top && Number(top.topY) > 0 ? Number(top.topY) - 1 : Math.round(player.blockY);
}

// Corners of the frame footprint in the XZ plane.
var halfW = Math.floor(frameWidth / 2);
var x1, x2, z1, z2;
if (axis === 'x') {
  x1 = centerX - halfW; x2 = x1 + frameWidth - 1;
  z1 = centerZ; z2 = centerZ;
} else {
  z1 = centerZ - halfW; z2 = z1 + frameWidth - 1;
  x1 = centerX; x2 = centerX;
}

var survey = skills.call('survey_build_site', {
  x1: Math.min(x1, x2), z1: Math.min(z1, z2),
  x2: Math.max(x1, x2), z2: Math.max(z1, z2),
  height: frameHeight + 2, y: floorY, allow_liquid: false
});
if (survey && survey.blockers && survey.blockers.length) {
  return { ok: false, error: 'portal site is blocked: ' + survey.blockers.join('; '),
    site: survey.site, hint: 'choose flat solid ground clear of chests and water' };
}

// Build the ordered placement list: bottom, sides, top.
var frame = [];
for (var c = 0; c < frameWidth; c++) {
  var bx = axis === 'x' ? x1 + c : x1;
  var bz = axis === 'x' ? z1 : z1 + c;
  frame.push({ x: bx, y: floorY + 1, z: bz, phase: 'bottom' });
}
for (var r = 1; r < frameHeight - 1; r++) {
  var ly = floorY + 1 + r;
  if (axis === 'x') {
    frame.push({ x: x1, y: ly, z: z1, phase: 'side' });
    frame.push({ x: x2, y: ly, z: z1, phase: 'side' });
  } else {
    frame.push({ x: x1, y: ly, z: z1, phase: 'side' });
    frame.push({ x: x1, y: ly, z: z2, phase: 'side' });
  }
}
for (var t = 0; t < frameWidth; t++) {
  var tx = axis === 'x' ? x1 + t : x1;
  var tz = axis === 'x' ? z1 : z1 + t;
  frame.push({ x: tx, y: floorY + frameHeight, z: tz, phase: 'top' });
}

// Interior: 2 wide, 3 tall.
var interior = [];
for (var ir = 1; ir < frameHeight - 1; ir++) {
  for (var ic = 1; ic < frameWidth - 1; ic++) {
    var ix = axis === 'x' ? x1 + ic : x1;
    var iz = axis === 'x' ? z1 : z1 + ic;
    interior.push({ x: ix, y: floorY + 1 + ir, z: iz });
  }
}

var have = countInInventory(drone.state().inventory, 'obsidian');
var audit = { obsidianRequired: frame.length, obsidianHave: have,
  missing: Math.max(0, frame.length - have) };

if (cfg.dry_run === true) {
  return { ok: true, dryRun: true, frame: { x1: x1, z1: z1, x2: x2, z2: z2,
      y: floorY, axis: axis, width: frameWidth, height: frameHeight },
    audit: audit, survey: { slope: survey && survey.slope,
      warnings: survey && survey.warnings },
    next: 'mine or withdraw ' + audit.missing + ' obsidian, then re-run without dry_run' };
}

if (audit.missing > 0) {
  return { ok: false, error: 'need ' + frame.length + ' obsidian, have ' + have,
    audit: audit,
    hint: 'mine obsidian with a diamond pickaxe or withdraw it, then retry' };
}

var cleared = 0;
if (cfg.clear !== false) {
  // Clear the interior and the headroom above it, top-down.
  var clearList = interior.slice(0);
  for (var ci = 0; ci < clearList.length; ci++) {
    var cell = clearList[ci];
    try {
      var probe = drone.getBlock({ x: cell.x, y: cell.y, z: cell.z });
      if (!probe || probe.isAir) continue;
      if (probe.isLiquid === true || Number(probe.hardness) < 0) continue;
      var removed = drone.breakBlock({ x: cell.x, y: cell.y, z: cell.z });
      if (removed && removed.broken) cleared++;
    } catch (clearError) {
      log('interior clear skipped: ' + clearError.message);
    }
  }
}

var placed = 0;
var failed = 0;
var missing = 0;
for (var f = 0; f < frame.length; f++) {
  var target = frame[f];
  var slot = drone.state();
  if (slot && countInInventory(slot.inventory, 'obsidian') <= 0) {
    missing = frame.length - f;
    break;
  }
  try {
    // A frame cell inside a hillside must be opened before obsidian goes in.
    var occupied = drone.getBlock({ x: target.x, y: target.y, z: target.z });
    if (occupied && !occupied.isAir) {
      var isObsidian = nameMatches(occupied.name, occupied.registryName, 'obsidian');
      if (isObsidian) { placed++; continue; }
      if (occupied.isLiquid === true || Number(occupied.hardness) < 0) {
        failed++;
        continue;
      }
      drone.breakBlock({ x: target.x, y: target.y, z: target.z });
    }
    var result = drone.placeBlock({ x: target.x, y: target.y, z: target.z,
      block: 'obsidian' });
    if (result && result.placed) placed++;
    else {
      var existing = drone.getBlock({ x: target.x, y: target.y, z: target.z });
      if (existing && nameMatches(existing.name, existing.registryName, 'obsidian')) placed++;
      else failed++;
    }
  } catch (placeError) {
    failed++;
    log('frame place failed at ' + target.x + ',' + target.y + ',' + target.z + ': '
      + placeError.message);
  }
}

// Light the portal by igniting the bottom frame block; flint and steel places
// fire on the face's neighbour, which is the bottom interior block.
var ignited = false;
var portalBlocks = 0;
if (cfg.ignite !== false && missing === 0 && failed === 0) {
  var sparkX = axis === 'x' ? x1 + 1 : x1;
  var sparkZ = axis === 'x' ? z1 : z1 + 1;
  var spark = { x: sparkX, y: floorY + 1, z: sparkZ };
  try {
    drone.useItem({ item: 'flint_and_steel', x: spark.x, y: spark.y, z: spark.z, side: 1 });
    ignited = true;
  } catch (igniteError) {
    log('ignite failed: ' + igniteError.message);
  }
}
try {
  var scan = drone.scanBlocks({ filter: 'portal', radius: 6,
    x: Math.round((x1 + x2) / 2), y: floorY + 2, z: Math.round((z1 + z2) / 2) });
  portalBlocks = (scan && scan.count) || 0;
} catch (scanError) {
  log('portal verify scan failed: ' + scanError.message);
}

return {
  ok: portalBlocks > 0 || placed >= frame.length,
  frame: { x1: x1, z1: z1, x2: x2, z2: z2, y: floorY, axis: axis },
  placed: placed,
  failed: failed,
  missing: missing,
  cleared: cleared,
  ignited: ignited,
  portalBlocks: portalBlocks,
  audit: audit,
  hint: portalBlocks > 0
    ? 'portal is live: walk in to travel'
    : (ignited
      ? 'frame is complete but no portal block appeared; check the interior is 2x3 and fully air'
      : 'place flint and steel and use it on a bottom interior block')
};
