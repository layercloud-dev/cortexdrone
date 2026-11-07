// Deposit the whole inventory. params: { filter, chest:{x,y,z}, radius }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var target = cfg.chest;
if (!target) {
  var scan = drone.scanChests({ filter: 'all', radius: clamp(cfg.radius || 28, 4, 64) });
  var chests = (scan && scan.chests) || [];
  var best = null;
  for (var i = 0; i < chests.length; i++) {
    if (chests[i].freeSlots > 0) { best = chests[i]; break; }
  }
  if (!best && chests.length) best = chests[0];
  if (!best) {
    return { ok: false, error: 'no chest with free space within range',
      hint: 'craft and place a chest, or fly closer to storage' };
  }
  target = { x: best.x, y: best.y, z: best.z };
}

var state = drone.state();
if (state.totalItems <= 0) return { ok: true, deposited: 0, note: 'inventory already empty' };

try {
  drone.goto({ standoffOf: target, allow_dig: false });
} catch (navError) {
  log('could not approach chest: ' + navError.message);
}
var deposited = drone.depositChest({ x: target.x, y: target.y, z: target.z });
return {
  ok: !!(deposited && (deposited.deposited || 0) > 0),
  chest: target,
  deposited: (deposited && deposited.deposited) || 0
};
