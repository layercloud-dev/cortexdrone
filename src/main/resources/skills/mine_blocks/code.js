// Mining primitive. params: { filter, radius, count, veins }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var filter = cfg.filter || 'ores';
var radius = clamp(cfg.radius || LIMITS.scanRadiusDefault, LIMITS.blockScanRadiusMin, LIMITS.smallScanRadiusMax);
var wanted = clamp(cfg.count || 8, 1, SKILL_LIMITS.budget256);
var useVeins = cfg.veins !== false;
// Mining is destructive by definition: allow navigation to tunnel to buried
// targets unless the caller explicitly forbids it. Baritone's mine process
// breaks the blocks between the bot and the ore for exactly this reason.
var allowDig = cfg.allow_dig !== false;
var maxDigBlocks = clamp(cfg.max_dig_blocks || 96, LIMITS.minDigBlocks, LIMITS.maxDigBlocks);
var targets = [];

if (useVeins) {
  try {
    var veins = drone.scanVeins({ filter: filter, radius: radius });
    if (veins && veins.veins && veins.veins.length) {
      var ordered = veins.veins.slice(0);
      ordered.sort(function (a, b) {
        if (a.exposed !== b.exposed) return a.exposed ? -1 : 1;
        return a.dist - b.dist;
      });
      for (var v = 0; v < ordered.length && targets.length < wanted; v++) {
        var cluster = ordered[v].blocks || [];
        for (var b = 0; b < cluster.length && targets.length < wanted; b++) {
          var point = cluster[b];
          targets.push({ x: point[0], y: point[1], z: point[2] });
        }
      }
    }
  } catch (veinError) {
    log('vein scan unavailable: ' + veinError.message);
  }
}

if (!targets.length) {
  var found = drone.scanBlocks({ filter: filter, radius: radius });
  if (!found || !found.count) {
    return { ok: false, error: 'no ' + filter + ' within ' + radius + 'm',
      hint: 'move closer, widen the radius, or pick another filter' };
  }
  var list = found.blocks || [];
  for (var i = 0; i < list.length && targets.length < wanted; i++) {
    targets.push({ x: list[i].x, y: list[i].y, z: list[i].z });
  }
}

var mined = [];
var failed = 0;
for (var t = 0; t < targets.length; t++) {
  var state = drone.state();
  if (state.slots === 0) {
    return { ok: false, mined: mined.length, blocks: mined,
      remaining: targets.length - t,
      note: 'the drone has no inventory slots; install an inventory upgrade first' };
  }
  if (state.isFull) {
    return { ok: mined.length > 0, mined: mined.length, blocks: mined,
      remaining: targets.length - t, note: 'drone inventory is full' };
  }
  var target = targets[t];
  try {
    var moved = drone.goto({
      standoffOf: { x: target.x, y: target.y, z: target.z },
      allow_dig: allowDig,
      max_dig_blocks: maxDigBlocks,
      recovery_attempts: LIMITS.recoveryAttempts
    });
    if (!moved || moved.arrived === false) { failed++; continue; }
    var broke = drone.breakBlock({ x: target.x, y: target.y, z: target.z });
    if (broke && broke.broken) mined.push(target);
    else failed++;
  } catch (breakError) {
    failed++;
  }
}

return {
  ok: mined.length > 0,
  filter: filter,
  mined: mined.length,
  blocks: mined,
  failed: failed,
  remaining: Math.max(0, targets.length - mined.length),
  hint: mined.length ? '' : 'every target was unreachable; try a closer area or a wider scan'
};
