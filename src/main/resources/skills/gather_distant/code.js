// Locate a distant resource/biome, harvest it and optionally bring it back.
// params: { target, max_radius, count, deliver, allow_dig, player }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var target = cfg.target || cfg.filter || null;
if (!target) {
  return { ok: false, error: 'gather_distant needs a target block, ore or biome name' };
}
var maxRadius = clamp(cfg.max_radius || 1200, 64, 1500);
var count = clamp(cfg.count || 32, 1, 256);
var deliver = cfg.deliver !== false;

var located = drone.findDistant({ target: target, maxRadius: maxRadius });
if (!located || located.ok === false) {
  return { ok: false, error: 'distant search failed: ' + (located && located.error ? located.error : 'unknown') };
}
if (!located.found || !located.blocks || !located.blocks.length) {
  return { ok: false, error: 'no ' + target + ' found within ' + maxRadius + ' blocks',
    hint: 'increase max_radius or move to a different biome' };
}

var first = located.blocks[0];
var hit = { x: first[0], y: first[1], z: first[2] };
try {
  drone.goto({
    x: hit.x,
    y: hit.y + 2,
    z: hit.z,
    allow_dig: cfg.allow_dig === true,
    max_wait_ms: 180000,
    recovery_attempts: 12,
    max_dig_blocks: cfg.allow_dig === true ? 64 : 0
  });
} catch (travelError) {
  return { ok: false, error: 'could not reach distant ' + target + ': ' + travelError.message,
    locatedAt: hit, distance: located.distance || null,
    hint: 'retry with allow_dig:true if the target is underground or behind terrain' };
}

var mined = skills.call('mine_blocks', {
  filter: target,
  radius: clamp(cfg.mine_radius || 24, 4, 32),
  count: count,
  veins: cfg.veins !== false
});

var delivered = null;
if (deliver && mined && mined.mined) {
  delivered = skills.call('deliver_items', {
    filter: cfg.deliver_filter || 'all',
    count: 2304,
    player: cfg.player
  });
}

return {
  ok: !!(mined && mined.ok && mined.mined),
  target: target,
  locatedAt: hit,
  distance: located.distance || null,
  biome: located.biome || null,
  foundBlocks: located.count || 0,
  mined: mined && mined.mined ? mined.mined : 0,
  blocks: mined && mined.blocks ? mined.blocks : [],
  delivered: delivered && delivered.given ? delivered.given : 0,
  delivery: delivered,
  hint: mined && mined.ok ? '' : 'the target was located but could not be harvested'
};
