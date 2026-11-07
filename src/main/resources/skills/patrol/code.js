// Patrol loop. params: { waypoints, center, radius, loops, guard_radius, allow_dig }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var waypoints = cfg.waypoints ? cfg.waypoints.slice(0) : [];

if (!waypoints.length && cfg.center) {
  var center = cfg.center;
  var span = clamp(cfg.radius || 12, 4, 48);
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

var loops = clamp(cfg.loops || 1, 1, 6);
var guardRadius = clamp(cfg.guard_radius || 12, 4, 32);
var allowDig = cfg.allow_dig === true;
var reached = 0;
var failed = 0;
var kills = 0;

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
    var sweep = skills.call('guard_scan', { radius: guardRadius, max_attacks: 3 });
    if (sweep) kills += sweep.kills || 0;
  }
}

return { ok: reached > 0, loops: loops, waypoints: waypoints.length,
  reached: reached, failed: failed, kills: kills };
