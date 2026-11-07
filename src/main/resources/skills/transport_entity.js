// Entity transport. params: { entity, destination:{x,y,z}, player }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var filter = cfg.entity || 'animal';
if (String(filter).toLowerCase().indexOf('player') >= 0) {
  return { ok: false, error: 'use carry_player for players' };
}

var found = drone.getEntities({ radius: clamp(cfg.radius || 8, 4, 24), filter: filter });
if (!found || !found.count) {
  return { ok: false, error: 'no matching entity nearby', filter: filter };
}
var target = null;
for (var i = 0; i < found.entities.length; i++) {
  if (!found.entities[i].player) { target = found.entities[i]; break; }
}
if (!target) return { ok: false, error: 'only players matched; refusing' };

var destination = cfg.destination;
if (!destination) {
  var player = drone.getPlayer(cfg.player ? { name: cfg.player } : {});
  if (!player || player.ok === false) {
    return { ok: false, error: 'no destination and the player is offline' };
  }
  destination = { x: player.blockX + 4, y: player.blockY + 2, z: player.blockZ };
}

var picked;
try { picked = drone.pickEntity({ entity_id: target.id }); }
catch (pickError) { return { ok: false, error: 'pick failed: ' + pickError.message }; }
if (!picked || picked.picked !== true) return { ok: false, error: 'mount refused', result: picked };

var moved = null;
try {
  moved = drone.goto({ x: destination.x, y: destination.y, z: destination.z,
    allow_dig: cfg.allow_dig === true });
} catch (navError) { moved = { arrived: false, error: navError.message }; }

var dropped = null;
try { dropped = drone.unpickEntity({ entity_id: target.id }); }
catch (dropError) { try { dropped = drone.unpickEntity({}); } catch (ignored) {} }

return { ok: !!(moved && moved.arrived && dropped && dropped.unpicked),
  entity: target.name, entityId: target.id, destination: destination,
  arrived: !!(moved && moved.arrived), dismounted: !!(dropped && dropped.unpicked) };
