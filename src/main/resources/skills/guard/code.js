// Guard duty. params: { duration_s, radius, point, anchor, hold, max_attacks }
// Combat runs through guard_scan -> attack_to_death, so a threat is finished
// based on its health and the drone's damage per hit, not a blind swing cap.
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var seconds = clamp(cfg.duration_s || 120, 10, 240);
var radius = clamp(cfg.radius || 16, 4, 48);
var maxAttacks = clamp(cfg.max_attacks || 40, 1, 200);
var hold = cfg.hold !== false;

var anchor = cfg.point || null;
if (!anchor && cfg.anchor === 'player') {
  var player = drone.getPlayer({});
  if (player && player.ok !== false) {
    anchor = { x: player.blockX, y: player.blockY + 2, z: player.blockZ };
  }
}
if (!anchor && hold) {
  var state = drone.state();
  anchor = { x: Math.round(state.x), y: Math.round(state.y), z: Math.round(state.z) };
}

var deadline = Date.now() + seconds * 1000;
var sweeps = 0;
var kills = 0;
var threats = 0;
var blocked = [];
while (Date.now() < deadline) {
  var sweep = skills.call('guard_scan', {
    radius: radius,
    max_attacks: maxAttacks,
    max_targets: 4,
    duration_s: Math.max(3, Math.min(30, Math.round((deadline - Date.now()) / 1000)))
  });
  sweeps++;
  if (sweep) {
    kills += sweep.kills || 0;
    threats += sweep.threats || 0;
    var reasons = sweep.blocked || [];
    for (var b = 0; b < reasons.length; b++) {
      if (blocked.length < 6 && blocked.indexOf(reasons[b]) < 0) blocked.push(reasons[b]);
    }
  }
  if (hold && anchor) {
    var position = drone.state();
    var away = distance3(position.x, position.y, position.z, anchor.x, anchor.y, anchor.z);
    if (away > 4) {
      try {
        drone.goto({ x: anchor.x, y: anchor.y, z: anchor.z, allow_dig: false });
      } catch (returnError) {
        log('return to anchor failed: ' + returnError.message);
      }
    }
  }
  var left = deadline - Date.now();
  if (left <= 0) break;
  sleep(Math.min(5000, left));
}

return { ok: true, guardedSeconds: seconds, sweeps: sweeps,
  threatsSeen: threats, kills: kills, blocked: blocked, anchor: anchor };
