// Guard duty. params: { duration_s, radius, point, anchor, hold }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var seconds = clamp(cfg.duration_s || 120, 10, 240);
var radius = clamp(cfg.radius || 16, 4, 48);
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
while (Date.now() < deadline) {
  var sweep = skills.call('guard_scan', { radius: radius, max_attacks: 6 });
  sweeps++;
  if (sweep) {
    kills += sweep.kills || 0;
    threats += sweep.threats || 0;
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
  threatsSeen: threats, kills: kills, anchor: anchor };
