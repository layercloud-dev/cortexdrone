//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Carry a player safely. params: { player, destination:{x,y,z}, force_land }
//
// Deaths in the live log came from two mistakes this script now prevents:
//   1. flying low enough to clip a mountain or a tree, then dismounting above
//      a slope ("hit the ground too hard");
//   2. dismounting at a coordinate without checking the floor below it.
// The route is therefore split into a high cruise leg and a short, slow
// descent, and the rider is released only on a scanned safe landing cell.
var cfg = (typeof params === 'object' && params !== null) ? params : {};

function nameMatches(a, b) {
    if (!a || !b) return false;
    return String(a).toLowerCase() === String(b).toLowerCase();
}

// Releases the rider at the nearest safe column instead of abandoning them
// mid-air. Fills in the caller's failure body with the landing evidence.
function releaseSafely(entity, body) {
    var safe = null;
    try {
        safe = drone.findSafeLanding({radius: 16, covered_ok: false});
    } catch (scanError) {
        safe = null;
    }
    if (safe && safe.safe === true && safe.spot) {
        try {
            drone.goto({
                x: safe.spot.hoverX, y: safe.spot.hoverY, z: safe.spot.hoverZ,
                allow_dig: false, max_wait_ms: 45000, recovery_attempts: 4
            });
        } catch (moveError) {
            // Keep the rider onboard if the descent fails.
        }
        var check = null;
        try {
            check = drone.findSafeLanding({x: safe.spot.x, y: safe.spot.y, z: safe.spot.z, radius: 1});
        } catch (verifyError) {
            check = null;
        }
        if (check && check.safe === true) {
            try {
                drone.unpickEntity({entity_id: entity.id});
                body.dismounted = true;
                body.emergencyLandedAt = {x: safe.spot.x, y: safe.spot.y, z: safe.spot.z};
                body.note = 'released at the nearest safe column instead of leaving the player airborne';
            } catch (dropError) {
                body.dismounted = false;
            }
        }
    }
    if (body.dismounted !== true) body.dismounted = false;
    return body;
}

var playerName = cfg.player;
var player = drone.getPlayer(playerName ? {name: playerName} : {});
if (!player || player.ok === false) {
    return {ok: false, error: 'player is offline or unavailable'};
}
if (player.riding === true) {
    return {
        ok: false, error: 'the player is already riding something',
        ridingEntity: player.ridingEntityName || 'unknown',
        hint: 'ask them to dismount, or fly the drone with goto instead'
    };
}

var destination = cfg.destination;
if (!destination && player.lookBlock) {
    destination = {x: player.lookBlock.x, y: player.lookBlock.y + 2, z: player.lookBlock.z};
}
if (!destination) {
    return {
        ok: false, error: 'no destination',
        hint: 'look at a block within 80m or pass destination:{x,y,z}'
    };
}

var found = drone.getEntities({radius: 12, filter: 'player'});
if (!found || !found.count) {
    return {
        ok: false, error: 'player entity not found near the drone',
        hint: 'fly to the player first with goto (they must be within 12m)'
    };
}
var target = null;
for (var i = 0; i < found.entities.length; i++) {
    var entity = found.entities[i];
    if (nameMatches(entity.name, playerName) || nameMatches(entity.name, player.name)) {
        target = entity;
        break;
    }
}
if (!target && !playerName) target = found.entities[0];
if (!target) {
    return {
        ok: false, error: 'that player is not within 12m of the drone',
        requested: playerName || player.name, nearby: found.entities.length
    };
}
// Never carry an entity id that went stale between recon and pickup: the live
// log lost two turns to "No pickable entity with id 575".
var live = drone.getEntities({radius: 16, filter: 'player'});
var verified = null;
for (var v = 0; live && v < live.entities.length; v++) {
    if (live.entities[v].id === target.id) {
        verified = live.entities[v];
        break;
    }
}
if (!verified) {
    return {ok: false, error: 'the player entity disappeared before pickup', entityId: target.id};
}
target = verified;
if (target.dist !== undefined && target.dist > 8) {
    return {
        ok: false, error: 'player is ' + target.dist + 'm away; the drone must come closer',
        hint: 'goto the player, then retry carry_player', target: target
    };
}

var pNow = drone.state();
if (!pNow || pNow.ok === false) return {ok: false, error: 'no live drone state'};
var startY = Math.floor(pNow.y);
var destX = Math.round(destination.x);
var destY = Math.round(destination.y);
var destZ = Math.round(destination.z);

// Underground players leave through a 2x2 shaft, never through a 1-wide hole.
// A rider is wider and much taller than the drone, so a one-block tunnel
// suffocates them. Open the shaft first, then board and ascend it vertically.
var playerUnderground = player.underground === true;
var droneTop = null;
try {
    droneTop = drone.getTopY({x: Math.floor(pNow.x), z: Math.floor(pNow.z)});
} catch (topReadError) {
    droneTop = null;
}
var droneUnderground = droneTop && droneTop.ok !== false && droneTop.topY
    && pNow.y < droneTop.topY - 2;

if (playerUnderground || droneUnderground) {
    // Move into the player's own column so the shaft opens directly above them.
    try {
        drone.goto({
            x: player.blockX + 0.5, y: player.blockY + 0.35, z: player.blockZ + 0.5,
            allow_dig: true, max_dig_blocks: 64, max_wait_ms: 60000, recovery_attempts: 8
        });
    } catch (reachError) {
        // Not fatal: the shaft may still be opened from the drone's column below.
    }
    var escape = null;
    try {
        escape = skills.call('escape_cave', {x: player.blockX, z: player.blockZ});
    } catch (escapeError) {
        escape = {ok: false, error: escapeError.message};
    }
    if (!escape || escape.ok !== true) {
        return {
            ok: false,
            error: 'the player is underground and no 2x2 escape shaft could be opened',
            stage: 'cave-escape', escape: escape,
            hint: 'refusing to board and fly through a 1-wide hole: the rider would suffocate'
        };
    }
    var afterEscape = drone.state();
    if (afterEscape && afterEscape.ok !== false) {
        pNow = afterEscape;
        startY = Math.floor(pNow.y);
    }
}

// Work out how high the safety cruise should be: above every terrain column on
// the route, not just above the destination. Mountains and jungle canopies are
// exactly what the drone hit before.
var cruiseY = Math.max(startY, destY) + 12;
var peak = cruiseY;
var samples = 0;
for (var t = 0; t <= 1.0001; t += 0.125) {
    var sx = Math.round(pNow.x + (destX - pNow.x) * t);
    var sz = Math.round(pNow.z + (destZ - pNow.z) * t);
    var top = null;
    try {
        top = drone.getTopY({x: sx, z: sz});
    } catch (topError) {
        top = null;
    }
    if (top && top.ok !== false && top.topY) {
        samples++;
        // 8 blocks of clearance keeps the rider out of canopy and cliff faces.
        if (top.topY + 8 > peak) peak = top.topY + 8;
    }
}
cruiseY = Math.min(LIMITS.landingY, Math.max(cruiseY, peak));

// The cave escape can take minutes; re-resolve the rider and their distance
// immediately before boarding instead of trusting the recon snapshot.
var fresh = drone.getEntities({radius: 16, filter: 'player'});
var freshTarget = null;
for (var f = 0; fresh && f < fresh.entities.length; f++) {
    if (fresh.entities[f].id === target.id) {
        freshTarget = fresh.entities[f];
        break;
    }
}
if (!freshTarget) {
    return {
        ok: false, error: 'the player moved out of range during the cave escape',
        hint: 'fly to the player again, then retry carry_player', entityId: target.id
    };
}
target = freshTarget;
if (target.dist !== undefined && target.dist > 8) {
    try {
        drone.goto({
            x: target.x, y: target.y + 1, z: target.z,
            allow_dig: true, max_dig_blocks: 32, max_wait_ms: 60000, recovery_attempts: 6
        });
    } catch (approachError) {
        // The pickup below will report the real distance if this did not work.
    }
}

var pickup = null;
try {
    pickup = drone.pickEntity({entity_id: target.id});
} catch (pickError) {
    return {ok: false, error: 'could not pick up the player: ' + pickError.message};
}
if (!pickup || pickup.picked !== true) {
    return {ok: false, error: 'mount refused', result: pickup};
}

// 1) climb straight up first: a rider must never travel sideways through a
//    hillside at ground level
var climb = null;
try {
    climb = drone.goto({
        x: Math.floor(pNow.x) + 0.5, y: cruiseY, z: Math.floor(pNow.z) + 0.5,
        allow_dig: false, max_wait_ms: 60000, recovery_attempts: 8
    });
} catch (climbError) {
    climb = {arrived: false, error: climbError.message};
}
if (!climb || climb.arrived === false) {
    return releaseSafely(target, {
        ok: false,
        error: 'could not climb to a safe cruise altitude before flying',
        stage: 'climb', cruiseY: cruiseY, climb: climb
    });
}

// 2) cruise high above the terrain
var cruise = null;
try {
    cruise = drone.goto({
        x: destX + 0.5, y: cruiseY, z: destZ + 0.5,
        allow_dig: false, max_wait_ms: LIMITS.maxWaitMs, recovery_attempts: LIMITS.recoveryAttempts
    });
} catch (cruiseError) {
    cruise = {arrived: false, error: cruiseError.message};
}
if (!cruise || cruise.arrived === false) {
    return releaseSafely(target, {
        ok: false,
        error: 'the high route failed; the player was set down at the last safe column',
        stage: 'cruise', cruiseY: cruiseY, cruise: cruise
    });
}

// 3) find a safe landing cell for the rider, then descend to it slowly
var landing = null;
try {
    landing = drone.findSafeLanding({x: destX, y: destY, z: destZ, radius: LIMITS.landingRadiusDefault});
} catch (landError) {
    landing = null;
}
if (!landing || landing.ok === false) {
    return releaseSafely(target, {
        ok: false,
        error: 'could not scan the destination for a safe landing spot',
        stage: 'landing-scan', cruiseY: cruiseY, scanError: landing && landing.error
    });
}
if (landing.safe !== true || !landing.spot) {
    return releaseSafely(target, {
        ok: false,
        error: 'no safe landing place near the destination',
        stage: 'landing-scan', requested: {x: destX, y: destY, z: destZ},
        hint: 'the drone is still carrying the player at a safe altitude; choose another '
            + 'destination',
        landing: landing
    });
}
var spot = landing.spot;

var descend = null;
try {
    descend = drone.goto({
        x: spot.hoverX, y: spot.hoverY, z: spot.hoverZ,
        allow_dig: false, max_wait_ms: 90000, recovery_attempts: 8
    });
} catch (descendError) {
    descend = {arrived: false, error: descendError.message};
}
if (!descend || descend.arrived === false) {
    return releaseSafely(target, {
        ok: false,
        error: 'could not reach the selected landing cell',
        stage: 'descend', spot: spot, descend: descend
    });
}

// 4) re-verify the exact cell at the last moment: the world can change while
//    the drone descends (a leaf decay, a creeper, a flowing liquid)
var finalCheck = null;
try {
    finalCheck = drone.findSafeLanding({x: spot.x, y: spot.y, z: spot.z, radius: 2});
} catch (finalError) {
    finalCheck = null;
}
if (!finalCheck || finalCheck.safe !== true) {
    return releaseSafely(target, {
        ok: false,
        error: 'the landing cell changed and is no longer safe; the player is still on board',
        stage: 'final-check', spot: spot, landing: finalCheck
    });
}

var dropped = null;
try {
    dropped = drone.unpickEntity({entity_id: target.id});
} catch (dropError) {
    try {
        dropped = drone.unpickEntity({});
    } catch (ignored) {
    }
}
if (!dropped || dropped.unpicked !== true) {
    return {
        ok: false,
        error: 'the player could not be dismounted; keep them onboard and land somewhere open',
        stage: 'dismount', spot: spot, dropped: dropped
    };
}

var after = drone.getPlayer(playerName ? {name: playerName} : {});
return {
    ok: true,
    player: target.name,
    destination: {x: destX, y: destY, z: destZ},
    landedAt: {x: spot.x, y: spot.y, z: spot.z},
    surfaceLevel: spot.surfaceLevel === true,
    openSky: spot.openSky === true,
    cruiseY: cruiseY,
    routeSamples: samples,
    arrived: true,
    dismounted: true,
    movedToSafeSpot: landing.movedFromRequest === true,
    playerAfter: after && after.ok !== false
        ? {x: after.blockX, y: after.blockY, z: after.blockZ} : null
};
