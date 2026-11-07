// Torch grid. params: { radius, spacing, point }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var radius = clamp(cfg.radius || 8, 2, 16);
var spacing = clamp(cfg.spacing || 4, 2, 8);
var state = drone.state();
var torches = countInInventory(state.inventory, 'torch');
if (torches <= 0) {
  try { drone.craft({ filter: 'torch', count: 16 }); } catch (craftError) {
    log('torch craft failed: ' + craftError.message);
  }
  torches = countInInventory(drone.state().inventory, 'torch');
}
if (torches <= 0) {
  return { ok: false, error: 'no torches and not enough coal/sticks to craft them',
    hint: 'gather coal and wood first' };
}

var center = cfg.point || { x: Math.round(state.x), y: Math.round(state.y), z: Math.round(state.z) };
var floorY = Math.round(center.y);
var placed = 0;
var skipped = 0;

for (var dx = -radius; dx <= radius && placed < torches; dx += spacing) {
  for (var dz = -radius; dz <= radius && placed < torches; dz += spacing) {
    var x = center.x + dx;
    var z = center.z + dz;
    var above = null;
    var below = null;
    try {
      above = drone.getBlock({ x: x, y: floorY, z: z });
      below = drone.getBlock({ x: x, y: floorY - 1, z: z });
    } catch (readError) {
      skipped++;
      continue;
    }
    if (!above || !above.isAir) { skipped++; continue; }
    if (!below || below.isAir || below.isLiquid || below.passable === true) { skipped++; continue; }
    try {
      var result = drone.placeBlock({ x: x, y: floorY, z: z, block: 'torch' });
      if (result && result.placed) placed++;
    } catch (placeError) {
      skipped++;
    }
  }
}

return { ok: placed > 0, placed: placed, skipped: skipped,
  torchesLeft: countInInventory(drone.state().inventory, 'torch'),
  note: 'place at the floor level you want to keep lit; repeat one level up in tall rooms' };
