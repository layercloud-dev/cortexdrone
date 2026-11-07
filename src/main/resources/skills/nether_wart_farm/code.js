// Nether wart harvest + replant. params: { radius, limit, replant, point }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var radius = clamp(cfg.radius || 24, 4, 32);
var limit = clamp(cfg.limit || 24, 1, 64);
var replant = cfg.replant !== false;
var matureMeta = 3;

var request = { filter: 'nether_wart', radius: radius, limit: 400 };
if (cfg.point) {
  request.x = Math.round(cfg.point.x);
  request.y = Math.round(cfg.point.y);
  request.z = Math.round(cfg.point.z);
}
var found = drone.scanBlocks(request);
if (!found || !found.count) {
  return { ok: false, error: 'no nether wart within ' + radius + 'm',
    hint: 'nether wart grows on soul sand in nether fortresses or your own farm' };
}

var mature = [];
var growing = 0;
var list = found.blocks || [];
for (var i = 0; i < list.length && mature.length < limit; i++) {
  var block = list[i];
  if (Number(block.meta) >= matureMeta) mature.push(block);
  else growing++;
}
if (!mature.length) {
  return { ok: true, harvested: 0, growing: growing,
    note: 'no fully grown nether wart yet; stage 3 is ready, come back later' };
}

var harvested = 0;
var replanted = 0;
var failed = 0;
for (var h = 0; h < mature.length; h++) {
  var plant = mature[h];
  try {
    drone.goto({ standoffOf: { x: plant.x, y: plant.y, z: plant.z }, allow_dig: false });
    var broke = drone.breakBlock({ x: plant.x, y: plant.y, z: plant.z });
    if (!broke || !broke.broken) { failed++; continue; }
    harvested++;
  } catch (breakError) {
    failed++;
    log('wart skip at ' + plant.x + ',' + plant.y + ',' + plant.z + ': ' + breakError.message);
    continue;
  }

  if (!replant) continue;
  try {
    if (countInInventory(drone.state().inventory, 'nether_wart') <= 0) continue;
    var below = drone.getBlock({ x: plant.x, y: plant.y - 1, z: plant.z });
    if (!below || !nameMatches(below.name, below.registryName, 'soul sand', below.oreDict)) continue;
    drone.goto({ standoffOf: { x: plant.x, y: plant.y - 1, z: plant.z }, allow_dig: false });
    var used = drone.useItem({ item: 'nether_wart', x: plant.x, y: plant.y - 1,
      z: plant.z, side: 1 });
    if (used && used.used) replanted++;
  } catch (plantError) {
    log('wart replant skipped: ' + plantError.message);
  }
}

var collected = null;
try {
  collected = skills.call('collect_drops', { radius: Math.min(16, radius),
    filter: 'nether_wart', max_clusters: 10 });
} catch (collectError) {
  log('drop collection skipped: ' + collectError.message);
}

return {
  ok: harvested > 0,
  harvested: harvested,
  replanted: replanted,
  growing: growing,
  failed: failed,
  wartLeft: countInInventory(drone.state().inventory, 'nether_wart'),
  collected: collected && collected.collected,
  hint: harvested > 0 ? ''
    : 'plants are visible but unreachable; fly closer to the soul sand rows'
};
