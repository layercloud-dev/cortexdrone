// Patrol loop. params: { waypoints, center, radius, loops, guard_radius,
// guard_attacks, allow_dig }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var waypoints = cfg.waypoints ? cfg.waypoints.slice(0) : [];

if (!waypoints.length && cfg.center) {
  var center = cfg.center;
  var span = clamp(cfg.radius || SKILL_LIMITS.shortRadiusDefault, LIMITS.blockScanRadiusMin, LIMITS.mediumRadiusMax);
  waypoints = [
    { x: center.x + span, y: center.y, z: center.z + span },
    { x: center.x - span, y: center.y, z: center.z + span },
    { x: center.x - span, y: center.y, z: center.z - span },
    { x: center.x + span, y: center.y, z: center.z - span }
  ];
}
if (!waypoints.length) {
  return { ok: false, error: 'patrol needs waypoints or a center',
    hint: 'pass waypoints:[{x,y,z}] or center:{x,y,z} and radius' };
}

var loops = clamp(cfg.loops || 1, 1, SKILL_LIMITS.patrolLoopsMax);
var guardRadius = clamp(cfg.guard_radius || SKILL_LIMITS.shortRadiusDefault, LIMITS.blockScanRadiusMin, LIMITS.smallScanRadiusMax);
var guardAttacks = clamp(cfg.guard_attacks || 40, 1, SKILL_LIMITS.attackCap200);
var allowDig = cfg.allow_dig === true;
var reached = 0;
var failed = 0;
var kills = 0;
var blocked = [];

for (var loop = 0; loop < loops; loop++) {
  for (var i = 0; i < waypoints.length; i++) {
    var point = waypoints[i];
    try {
      var moved = drone.goto({ x: point.x, y: point.y, z: point.z, allow_dig: allowDig });
      if (moved && moved.arrived) reached++; else failed++;
    } catch (navError) {
      failed++;
      log('waypoint unreachable: ' + navError.message);
    }
    var sweep = skills.call('guard_scan', {
      radius: guardRadius,
      max_attacks: guardAttacks,
      max_targets: 4
    });
    if (sweep) {
      kills += sweep.kills || 0;
      var reasons = sweep.blocked || [];
      for (var b = 0; b < reasons.length; b++) {
        if (blocked.length < 6 && blocked.indexOf(reasons[b]) < 0) blocked.push(reasons[b]);
      }
    }
  }
}

return { ok: reached > 0, loops: loops, waypoints: waypoints.length,
  reached: reached, failed: failed, kills: kills, blocked: blocked };
