// One defensive sweep. params: { radius, max_attacks, target }
// Each threat is finished with attack_to_death: the swing count comes from the
// mob's health and the drone's real damage per hit, not a fixed budget.
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var radius = clamp(cfg.radius || 16, 4, 48);
var maxAttacks = clamp(cfg.max_attacks || 40, 1, 200);
var target = cfg.target || 'hostile';
var maxTargets = clamp(cfg.max_targets || 4, 1, 16);
var seconds = clamp(cfg.duration_s || 30, 3, 120);

var found = drone.getEntities({ radius: radius, filter: target });
if (!found || !found.count) {
  return { ok: true, threats: 0, attacks: 0, kills: 0 };
}

var list = found.entities.slice(0);
list.sort(function (a, b) { return a.dist - b.dist; });

var attacks = 0;
var kills = 0;
var names = [];
var engaged = 0;
var blocked = [];
var deadline = Date.now() + seconds * 1000;
for (var i = 0; i < list.length && engaged < maxTargets && attacks < maxAttacks
    && Date.now() < deadline; i++) {
  var mob = list[i];
  if (mob.dist > radius) continue;
  if (mob.player) continue;
  engaged++;
  if (names.length < 8) names.push(mob.name);
  var budget = Math.max(1, maxAttacks - attacks);
  var result = null;
  try {
    result = skills.call('attack_to_death', {
      entity_id: mob.id,
      max_attacks: budget,
      max_seconds: Math.max(3, Math.min(seconds, Math.round((deadline - Date.now()) / 1000)))
    });
  } catch (killError) {
    log('sweep kill failed for ' + mob.name + ': ' + killError.message);
  }
  if (!result) continue;
  attacks += result.attacks || 0;
  if (result.killed) kills++;
  else if (result.blocker && blocked.length < 4 && blocked.indexOf(result.blocker) < 0) {
    blocked.push(result.blocker);
  }
}

return { ok: true, threats: found.count, attacks: attacks, kills: kills,
  targets: names, blocked: blocked, radius: radius };
