// Entity transport. params: { entity, destination:{x,y,z}, player, radius }
//
// Same landing rules as carry_player: animals are not players, but dropping a
// cow from 30 blocks still kills it and a mob released inside a wall
// suffocates. The route is climb -> cruise -> scan -> descend -> release.
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var filter = cfg.entity || 'animal';
if (String(filter).toLowerCase().indexOf('player') >= 0) {
  return { ok: false, error: 'use carry_player for players' };
}

// Release at the nearest safe column instead of mid-air.
function releaseSafely(entity, body) {
  var safe = null;
  try {
    safe = drone.findSafeLanding({ radius: 16, covered_ok: false });
  } catch (scanError) {
    safe = null;
  }
  if (safe && safe.safe === true && safe.spot) {
    try {
      drone.goto({ x: safe.spot.hoverX, y: safe.spot.hoverY, z: safe.spot.hoverZ,
        allow_dig: false, max_wait_ms: 45000, recovery_attempts: 4 });
    } catch (moveError) {
      // Keep the entity onboard if the descent fails.
    }
    var check = null;
    try {
      check = drone.findSafeLanding({ x: safe.spot.x, y: safe.spot.y, z: safe.spot.z, radius: 1 });
    } catch (verifyError) {
      check = null;
    }
    if (check && check.safe === true) {
      try {
        drone.unpickEntity({ entity_id: entity.id });
        body.dismounted = true;
        body.emergencyLandedAt = { x: safe.spot.x, y: safe.spot.y, z: safe.spot.z };
      } catch (dropError) {
        body.dismounted = false;
      }
    }
  }
  if (body.dismounted !== true) body.dismounted = false;
  return body;
}

var radius = clamp(cfg.radius || 10, 4, 24);
var found = drone.getEntities({ radius: radius, filter: filter });
if (!found || !found.count) {
  return { ok: false, error: 'no matching entity nearby', filter: filter };
}
var target = null;
for (var i = 0; i < found.entities.length; i++) {
  var candidate = found.entities[i];
  if (!candidate.player) { target = candidate; break; }
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

var destX = Math.round(destination.x);
var destY = Math.round(destination.y);
var destZ = Math.round(destination.z);
var pNow = drone.state();
if (!pNow || pNow.ok === false) return { ok: false, error: 'no live drone state' };

// A caged or cave-bound mob needs an escape shaft too: releasing it inside a
// 2-block pocket under a mountain is not transport.
var top = null;
try { top = drone.getTopY({ x: destX, z: destZ }); } catch (topError) { top = null; }
if (top && top.ok !== false && destY < top.topY - 4) {
  var escape = null;
  try {
    escape = skills.call('escape_cave', { x: destX, z: destZ, top_y: top.topY });
  } catch (escapeError) {
    escape = { ok: false, error: escapeError.message };
  }
  if (!escape || escape.ok !== true) {
    return { ok: false, error: 'the destination is underground and no 2x2 shaft could be opened',
      stage: 'cave-escape', escape: escape,
      hint: 'an entity released in a 1-wide hole suffocates; pick an open destination' };
  }
}

var cruiseY = Math.max(Math.floor(pNow.y), destY) + 10;
var peak = cruiseY;
for (var t = 0; t <= 1.0001; t += 0.2) {
  var sx = Math.round(pNow.x + (destX - pNow.x) * t);
  var sz = Math.round(pNow.z + (destZ - pNow.z) * t);
  var column = null;
  try { column = drone.getTopY({ x: sx, z: sz }); } catch (columnError) { column = null; }
  if (column && column.ok !== false && column.topY && column.topY + 6 > peak) {
    peak = column.topY + 6;
  }
}
cruiseY = Math.min(250, peak);

var picked = null;
try { picked = drone.pickEntity({ entity_id: target.id }); }
catch (pickError) { return { ok: false, error: 'pick failed: ' + pickError.message }; }
if (!picked || picked.picked !== true) return { ok: false, error: 'mount refused', result: picked };

// Climb, cruise, then descend: exactly the carry_player route.
try {
  drone.goto({ x: Math.floor(pNow.x) + 0.5, y: cruiseY, z: Math.floor(pNow.z) + 0.5,
    allow_dig: false, max_wait_ms: 60000, recovery_attempts: 8 });
} catch (climbError) {
  return releaseSafely(target, { ok: false, error: 'climb failed: ' + climbError.message,
    stage: 'climb', cruiseY: cruiseY });
}
var cruise = null;
try {
  cruise = drone.goto({ x: destX + 0.5, y: cruiseY, z: destZ + 0.5,
    allow_dig: false, max_wait_ms: 180000, recovery_attempts: 12 });
} catch (cruiseError) {
  cruise = { arrived: false, error: cruiseError.message };
}
if (!cruise || cruise.arrived === false) {
  return releaseSafely(target, { ok: false, error: 'the high route failed',
    stage: 'cruise', cruiseY: cruiseY, cruise: cruise });
}

var landing = null;
try { landing = drone.findSafeLanding({ x: destX, y: destY, z: destZ, radius: 10 }); }
catch (landError) { landing = null; }
if (!landing || landing.safe !== true || !landing.spot) {
  return releaseSafely(target, { ok: false, error: 'no safe landing place near the destination',
    stage: 'landing-scan', landing: landing });
}
var spot = landing.spot;

var descend = null;
try {
  descend = drone.goto({ x: spot.hoverX, y: spot.hoverY, z: spot.hoverZ,
    allow_dig: false, max_wait_ms: 90000, recovery_attempts: 8 });
} catch (descendError) {
  descend = { arrived: false, error: descendError.message };
}
if (!descend || descend.arrived === false) {
  return releaseSafely(target, { ok: false, error: 'could not reach the landing cell',
    stage: 'descend', spot: spot, descend: descend });
}

var finalCheck = null;
try { finalCheck = drone.findSafeLanding({ x: spot.x, y: spot.y, z: spot.z, radius: 2 }); }
catch (finalError) { finalCheck = null; }
if (!finalCheck || finalCheck.safe !== true) {
  return releaseSafely(target, { ok: false, error: 'the landing cell changed; entity still onboard',
    stage: 'final-check', spot: spot, landing: finalCheck });
}

var dropped = null;
try { dropped = drone.unpickEntity({ entity_id: target.id }); }
catch (dropError) { try { dropped = drone.unpickEntity({}); } catch (ignored) {} }

return {
  ok: !!(dropped && dropped.unpicked),
  entity: target.name,
  entityId: target.id,
  destination: { x: destX, y: destY, z: destZ },
  landedAt: { x: spot.x, y: spot.y, z: spot.z },
  cruiseY: cruiseY,
  arrived: true,
  dismounted: !!(dropped && dropped.unpicked)
};
