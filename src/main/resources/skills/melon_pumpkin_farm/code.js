// Melon/pumpkin harvest. params: { kind, radius, limit }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var kind = String(cfg.kind || 'melon').toLowerCase();
if (kind !== 'melon' && kind !== 'pumpkin') {
  return { ok: false, error: 'kind must be melon or pumpkin' };
}
var radius = clamp(cfg.radius || LIMITS.scanRadiusDefault, LIMITS.blockScanRadiusMin, LIMITS.smallScanRadiusMax);
var limit = clamp(cfg.limit || SKILL_LIMITS.smallLimitDefault, 1, SKILL_LIMITS.smallScanLimit);

var found = drone.scanBlocks({ filter: kind, radius: radius });
if (!found || !found.count) {
  return { ok: false, error: 'no ' + kind + ' within ' + radius + 'm',
    hint: 'melon and pumpkin need light 9+ and a dirt/grass block beside the stem' };
}

var fruits = [];
var stems = 0;
var list = found.blocks || [];
for (var i = 0; i < list.length && fruits.length < limit; i++) {
  var block = list[i];
  var registry = String(block.registryName || '').toLowerCase();
  var name = String(block.name || '').toLowerCase();
  if (registry.indexOf('stem') >= 0 || name.indexOf('stem') >= 0) { stems++; continue; }
  fruits.push(block);
}
if (!fruits.length) {
  return { ok: true, kind: kind, harvested: 0, stems: stems,
    note: 'only stems are present; the fruit has not grown yet' };
}

var harvested = 0;
var failed = 0;
for (var f = 0; f < fruits.length; f++) {
  var fruit = fruits[f];
  try {
    drone.goto({ standoffOf: { x: fruit.x, y: fruit.y, z: fruit.z }, allow_dig: false });
    var broke = drone.breakBlock({ x: fruit.x, y: fruit.y, z: fruit.z });
    if (broke && broke.broken) harvested++;
    else failed++;
  } catch (breakError) {
    failed++;
    log('fruit skip at ' + fruit.x + ',' + fruit.y + ',' + fruit.z + ': ' + breakError.message);
  }
}

var collected = null;
try {
  collected = skills.call('collect_drops', { radius: Math.min(LIMITS.collectRadiusMax, radius), filter: 'all',
    max_clusters: SKILL_LIMITS.maxClusters });
} catch (collectError) {
  log('drop collection skipped: ' + collectError.message);
}

return {
  ok: harvested > 0,
  kind: kind,
  harvested: harvested,
  stems: stems,
  failed: failed,
  collected: collected && collected.collected,
  note: 'stems were left in place so each one can grow another fruit'
};
