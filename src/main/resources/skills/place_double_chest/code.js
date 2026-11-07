// Double chest. params: { x, y, z, player }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var base = null;
if (cfg.x !== undefined && cfg.y !== undefined && cfg.z !== undefined) {
  base = { x: Math.round(cfg.x), y: Math.round(cfg.y), z: Math.round(cfg.z) };
} else {
  var player = drone.getPlayer(cfg.player ? { name: cfg.player } : {});
  if (!player || player.ok === false) return { ok: false, error: 'player is offline' };
  base = { x: player.blockX + 1, y: player.blockY, z: player.blockZ };
}

var have = countInInventory(drone.state().inventory, 'chest');
if (have < 2) {
  try { drone.craft({ filter: 'chest', count: 2 - have }); }
  catch (craftError) { log('chest craft failed: ' + craftError.message); }
  have = countInInventory(drone.state().inventory, 'chest');
}
if (have < 2) {
  return { ok: false, error: 'need 2 chests in the drone inventory, have ' + have,
    hint: 'gather 16 planks or take chests from the player first' };
}

var placed = 0;
var results = [];
for (var i = 0; i < 2; i++) {
  try {
    var result = drone.placeBlock({ x: base.x + i, y: base.y, z: base.z, block: 'chest' });
    if (result && result.placed) { placed++; results.push(true); }
    else results.push(false);
  } catch (placeError) {
    results.push(placeError.message);
  }
}
return { ok: placed === 2, placed: placed, at: base, results: results };
