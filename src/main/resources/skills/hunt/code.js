// Hunting. params: { target, radius, max_kills, duration_s }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var target = cfg.target || 'hostile';
var radius = clamp(cfg.radius || 24, 4, 64);
var maxKills = clamp(cfg.max_kills || 4, 1, 32);
var seconds = clamp(cfg.duration_s || 120, 15, 240);

var deadline = Date.now() + seconds * 1000;
var kills = 0;
var attacks = 0;
var names = [];
var emptyScans = 0;

while (kills < maxKills && Date.now() < deadline) {
  var found = drone.getEntities({ radius: radius, filter: target });
  if (!found || !found.count) {
    emptyScans++;
    if (emptyScans >= 2) break;
    sleep(1000);
    continue;
  }
  emptyScans = 0;
  var list = found.entities.slice(0);
  list.sort(function (a, b) { return a.dist - b.dist; });

  var victim = null;
  for (var i = 0; i < list.length; i++) {
    if (list[i].player) continue;
    victim = list[i];
    break;
  }
  if (!victim) {
    return { ok: false, error: 'only players matched the hunt filter', target: target };
  }
  if (names.length < 8 && names.indexOf(victim.name) < 0) names.push(victim.name);

  try {
    var hit = drone.attackEntity({ entity_id: victim.id });
    attacks++;
    if (hit && hit.killed) kills++;
  } catch (attackError) {
    // Target may have died, despawned or moved; the next scan re-targets.
    sleep(250);
  }
  if (attacks > maxKills * 40 + 20) break;
}

if ((kills > 0 || attacks > 0) && cfg.collect !== false) {
  try { drone.suckItems({ radius: 6 }); } catch (pickupError) {}
}

return {
  ok: attacks > 0,
  target: target,
  kills: kills,
  attacks: attacks,
  hunted: names,
  remainingSeconds: Math.max(0, Math.round((deadline - Date.now()) / 1000)),
  hint: attacks > 0 ? '' : 'no reachable target; widen radius or move to a spawn area'
};
