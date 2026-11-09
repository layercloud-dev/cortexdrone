// Bone-meal application. params: { radius, limit, target, point, craft_meal }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var radius = clamp(cfg.radius || SKILL_LIMITS.mediumRadiusDefault, LIMITS.blockScanRadiusMin, LIMITS.smallScanRadiusMax);
var limit = clamp(cfg.limit || SKILL_LIMITS.smallLimitDefault, 1, SKILL_LIMITS.largeScanLimit);
var target = String(cfg.target || 'crops').toLowerCase();

var filters = [];
if (target === 'crops' || target === 'crop') {
  filters = ['wheat', 'carrots', 'potatoes'];
} else if (target === 'saplings' || target === 'sapling' || target === 'trees') {
  filters = ['sapling'];
} else if (target === 'stems' || target === 'stem') {
  filters = ['melon_stem', 'pumpkin_stem', 'stem'];
} else if (target === 'cane' || target === 'sugarcane') {
  return { ok: false, target: target,
    error: 'bone meal does not work on sugar cane in 1.7.10',
    hint: 'wait for the cane to grow, or use sugarcane_farm to harvest what is ready' };
} else if (target === 'wart' || target === 'nether_wart') {
  return { ok: false, target: target,
    error: 'bone meal does not work on nether wart in 1.7.10',
    hint: 'wart grows on its own random ticks; use nether_wart_farm to harvest mature plants' };
} else {
  filters = [target];
}

var meal = countInInventory(drone.state().inventory, 'bone_meal');
if (meal <= 0 && cfg.craft_meal !== false) {
  try {
    skills.call('craft_items', { filter: 'bone_meal', count: 12 });
  } catch (craftError) {
    log('bone meal craft failed: ' + craftError.message);
  }
  meal = countInInventory(drone.state().inventory, 'bone_meal');
}
if (meal <= 0) {
  return { ok: false, error: 'no bone meal in the drone inventory',
    hint: 'bring bones and craft bone meal, or loot a dungeon' };
}

var applied = 0;
var attempted = 0;
var seen = {};
for (var f = 0; f < filters.length && applied < limit; f++) {
  var request = { filter: filters[f], radius: radius, limit: SKILL_LIMITS.farmScanLimit };
  if (cfg.point) {
    request.x = Math.round(cfg.point.x);
    request.y = Math.round(cfg.point.y);
    request.z = Math.round(cfg.point.z);
  }
  var found = drone.scanBlocks(request);
  var list = (found && found.blocks) || [];
  for (var i = 0; i < list.length && applied < limit; i++) {
    var block = list[i];
    var key = block.x + ',' + block.y + ',' + block.z;
    if (seen[key]) continue;
    seen[key] = true;
    if (Number(block.meta) >= 7) continue; // already mature: meal would be wasted

    if (countInInventory(drone.state().inventory, 'bone_meal') <= 0) break;
    attempted++;
    try {
      drone.goto({ standoffOf: { x: block.x, y: block.y, z: block.z }, allow_dig: false });
      // Bone meal is used on the plant itself; side 1 targets its top face.
      var used = drone.useItem({ item: 'bone_meal', x: block.x, y: block.y, z: block.z, side: 1 });
      if (used && used.used) applied++;
    } catch (useError) {
      log('bone meal skip at ' + key + ': ' + useError.message);
    }
  }
}

return {
  ok: applied > 0,
  target: target,
  applied: applied,
  attempted: attempted,
  spent: applied,
  mealLeft: countInInventory(drone.state().inventory, 'bone_meal'),
  hint: applied > 0 ? ''
    : 'no plant accepted bone meal; target crops must be below age 7, and cane/wart never accept it'
};
