// Material retrieval. params: { filter, count, radius }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var filter = cfg.filter || 'all';
var count = clamp(cfg.count || 64, 1, 2304);
var radius = clamp(cfg.radius || 32, 4, 64);

var found = drone.findItem({ filter: filter, radius: radius });
if (!found || found.found === false) {
  return { ok: false, error: 'no "' + filter + '" found in the drone, the player or nearby chests',
    searchedRadius: radius };
}
if ((found.inDroneCount || 0) >= count) {
  return { ok: true, withdrawn: 0, alreadyHeld: found.inDroneCount, source: 'drone' };
}

var remaining = count - (found.inDroneCount || 0);
var moved = 0;
var sources = [];

if ((found.inPlayerCount || 0) > 0) {
  try {
    var taken = drone.takeFromPlayer({ filter: filter, count: remaining });
    var tookCount = (taken && taken.transferred) || 0;
    if (tookCount > 0) { moved += tookCount; remaining -= tookCount; sources.push('player'); }
  } catch (takeError) {
    log('player take failed: ' + takeError.message);
  }
}

if (remaining > 0 && found.bestChest) {
  var chest = found.bestChest;
  try {
    drone.goto({ standoffOf: { x: chest.x, y: chest.y, z: chest.z }, allow_dig: false });
    var withdrawn = drone.withdrawChest({ filter: filter, count: remaining,
      x: chest.x, y: chest.y, z: chest.z });
    var got = (withdrawn && withdrawn.withdrawn) || 0;
    if (got > 0) { moved += got; remaining -= got; sources.push('chest'); }
  } catch (chestError) {
    log('chest withdraw failed: ' + chestError.message);
  }
}

return {
  ok: moved > 0 || (found.inDroneCount || 0) >= count,
  withdrawn: moved,
  filter: filter,
  sources: sources,
  available: found.totalAvailable || 0,
  hint: moved > 0 ? '' : 'the item exists but could not be moved; check reach and chest access'
};
