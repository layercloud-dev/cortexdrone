// Hunting. params: { target, radius, max_kills, duration_s, collect }
// Every kill goes through attack_to_death, so "attack until it dies" is
// health-based (attack_plan) instead of a blind swing budget.
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var target = cfg.target || 'hostile';
var radius = clamp(cfg.radius || LIMITS.scanRadiusDefault, LIMITS.blockScanRadiusMin, LIMITS.scanRadiusMax);
var maxKills = clamp(cfg.max_kills || 4, 1, SKILL_LIMITS.killCap32);
var seconds = clamp(cfg.duration_s || SKILL_LIMITS.guardDurationDefault, SKILL_LIMITS.huntDurationMin, SKILL_LIMITS.durationMax);
var maxAttacksPerMob = clamp(cfg.max_attacks || 60, 1, SKILL_LIMITS.attackCap200);

var deadline = Date.now() + seconds * 1000;
var kills = 0;
var attacks = 0;
var names = [];
var blocked = [];
var emptyScans = 0;
var stalls = 0;

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

  var result = null;
  try {
    result = skills.call('attack_to_death', {
      entity_id: victim.id,
      max_attacks: maxAttacksPerMob,
      max_seconds: Math.max(5, Math.min(60, Math.round((deadline - Date.now()) / 1000)))
    });
  } catch (killError) {
    log('kill failed for ' + victim.name + ': ' + killError.message);
  }
  if (result) {
    attacks += result.attacks || 0;
    if (result.killed) {
      kills++;
      stalls = 0;
    } else if (result.blocker && blocked.length < 4 && blocked.indexOf(result.blocker) < 0) {
      blocked.push(result.blocker);
    }
    if (!result.killed && !(result.attacks > 0)) {
      // The target survived without a single swing: retrying the same id would
      // spin. Drop it after a few scans and try the next entity.
      stalls++;
      if (stalls >= 3) break;
    }
  }
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
  blocked: blocked,
  remainingSeconds: Math.max(0, Math.round((deadline - Date.now()) / 1000)),
  hint: attacks > 0 ? '' : 'no reachable target; widen radius or move to a spawn area'
};
