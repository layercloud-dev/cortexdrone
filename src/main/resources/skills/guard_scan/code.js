// One defensive sweep. params: { radius, max_attacks, target }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var radius = clamp(cfg.radius || 16, 4, 48);
var maxAttacks = clamp(cfg.max_attacks || 6, 1, 24);
var target = cfg.target || 'hostile';

var found = drone.getEntities({ radius: radius, filter: target });
if (!found || !found.count) {
  return { ok: true, threats: 0, attacks: 0, kills: 0 };
}

var list = found.entities.slice(0);
list.sort(function (a, b) { return a.dist - b.dist; });

var attacks = 0;
var kills = 0;
var names = [];
for (var i = 0; i < list.length && attacks < maxAttacks; i++) {
  var mob = list[i];
  if (mob.dist > radius) continue;
  if (mob.player) continue;
  try {
    var hit = drone.attackEntity({ entity_id: mob.id });
    attacks++;
    if (names.length < 8) names.push(mob.name);
    if (hit && hit.killed) kills++;
  } catch (attackError) {
    // The mob may have died or moved out of reach; the next sweep re-reads.
    log('attack skipped: ' + attackError.message);
  }
}

return { ok: true, threats: found.count, attacks: attacks, kills: kills,
  targets: names, radius: radius };
