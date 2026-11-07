// Carry a player. params: { player, destination:{x,y,z} }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var playerName = cfg.player;
var player = drone.getPlayer(playerName ? { name: playerName } : {});
if (!player || player.ok === false) {
  return { ok: false, error: 'player is offline or unavailable' };
}

var destination = cfg.destination;
if (!destination && player.lookBlock) {
  destination = { x: player.lookBlock.x, y: player.lookBlock.y + 2, z: player.lookBlock.z };
}
if (!destination) {
  return { ok: false, error: 'no destination',
    hint: 'look at a block within 80m or pass destination:{x,y,z}' };
}

var found = drone.getEntities({ radius: 8, filter: 'player' });
if (!found || !found.count) {
  return { ok: false, error: 'player entity not found near the drone' };
}
var target = null;
for (var i = 0; i < found.entities.length; i++) {
  var entity = found.entities[i];
  if (!playerName || entity.name === playerName) { target = entity; break; }
}
if (!target) target = found.entities[0];

var picked;
try {
  picked = drone.pickEntity({ entity_id: target.id });
} catch (pickError) {
  return { ok: false, error: 'could not pick up the player: ' + pickError.message };
}
if (!picked || picked.picked !== true) {
  return { ok: false, error: 'mount refused', result: picked };
}

var moved = null;
try {
  moved = drone.goto({ x: destination.x, y: destination.y, z: destination.z,
    allow_dig: cfg.allow_dig === true, max_wait_ms: 180000 });
} catch (navError) {
  moved = { arrived: false, error: navError.message };
}

var dropped = null;
try {
  dropped = drone.unpickEntity({ entity_id: target.id });
} catch (dropError) {
  try { dropped = drone.unpickEntity({}); } catch (ignored) {}
}

return {
  ok: !!(moved && moved.arrived && dropped && dropped.unpicked),
  player: player.name || playerName || '',
  destination: destination,
  arrived: !!(moved && moved.arrived),
  dismounted: !!(dropped && dropped.unpicked),
  navigation: moved
};
