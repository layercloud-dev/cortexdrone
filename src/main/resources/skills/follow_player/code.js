//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Follow a player at a stable nearby hover point.
// params: { player, duration_s, distance, height, auto_pickup, allow_dig }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var playerName = cfg.player || null;
var seconds = clamp(cfg.duration_s || SKILL_LIMITS.guardDurationDefault, SKILL_LIMITS.followDurationMin, SKILL_LIMITS.durationMax);
var wantedDistance = clamp(cfg.distance || 2.2, 1.6, 4.0);
var hoverHeight = clamp(cfg.height || 1.1, 0.5, 2.0);
var autoPickup = cfg.auto_pickup !== false;
var allowDig = cfg.allow_dig === true;

function playerAt(name) {
    var request = name ? {name: name} : {};
    var p = drone.getPlayer(request);
    return (p && p.ok !== false) ? p : null;
}

function lookVector(p) {
    var yaw = Number(p.yaw || 0) * Math.PI / 180.0;
    var pitch = Number(p.pitch || 0) * Math.PI / 180.0;
    var cosPitch = Math.cos(pitch);
    return {
        x: -Math.sin(yaw) * cosPitch,
        y: -Math.sin(pitch),
        z: Math.cos(yaw) * cosPitch
    };
}

function lookingAtDrone(p, state) {
    var dx = state.x - p.x;
    var dy = state.y - (p.y + 1.62);
    var dz = state.z - p.z;
    var length = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (length < 0.001) return true;
    var look = lookVector(p);
    var dot = (look.x * dx + look.y * dy + look.z * dz) / length;
    return dot > 0.70;
}

function clearHover(x, y, z) {
    try {
        var feet = drone.getBlock({x: Math.round(x), y: Math.round(y), z: Math.round(z)});
        var head = drone.getBlock({x: Math.round(x), y: Math.round(y) + 1, z: Math.round(z)});
        return feet && feet.isAir && head && head.isAir;
    } catch (readError) {
        return false;
    }
}

function chooseAnchor(p, state) {
    var dx = state.x - p.x;
    var dz = state.z - p.z;
    var length = Math.sqrt(dx * dx + dz * dz);
    var baseAngle = length > 0.01 ? Math.atan2(dz, dx) : 0;
    var y = Math.round(p.y + hoverHeight);
    var angles = [0, 45, 90, 135, 180, 225, 270, 315];
    // Prefer the side the drone already occupies so it never crosses the player.
    for (var i = 0; i < angles.length; i++) {
        var angle = baseAngle + angles[i] * Math.PI / 180.0;
        var x = Math.round(p.x + Math.cos(angle) * wantedDistance);
        var z = Math.round(p.z + Math.sin(angle) * wantedDistance);
        if (clearHover(x, y, z)) return {x: x, y: y, z: z};
    }
    return {x: Math.round(p.x), y: Math.round(p.y + 2), z: Math.round(p.z)};
}

function playerMovedTooFar(anchor, p) {
    var horizontal = distance3(anchor.x, 0, anchor.z, p.x, 0, p.z);
    return horizontal > 3.8 || horizontal < 1.2 || !clearHover(anchor.x, anchor.y, anchor.z);
}

var first = playerAt(playerName);
if (!first) {
    return {ok: false, error: 'player is offline or unavailable', player: playerName || ''};
}

var anchor = null;
var deadline = Date.now() + seconds * 1000;
var moves = 0;
var held = 0;
var picked = 0;
var errors = 0;

while (Date.now() < deadline) {
    var p = playerAt(playerName);
    if (!p) break;
    var state = drone.state();
    if (!state || state.ok === false) break;

    if (lookingAtDrone(p, state) && anchor) {
        // Keep the drone still while the player aims at it (right-click access).
        held++;
    } else {
        if (!anchor || playerMovedTooFar(anchor, p)) {
            anchor = chooseAnchor(p, state);
        }
        if (anchor) {
            var away = distance3(state.x, state.y, state.z, anchor.x, anchor.y, anchor.z);
            if (away > 1.25) {
                try {
                    drone.goto({
                        x: anchor.x,
                        y: anchor.y,
                        z: anchor.z,
                        allow_dig: allowDig,
                        max_wait_ms: 6000,
                        recovery_attempts: 2,
                        max_dig_blocks: allowDig ? 8 : LIMITS.minDigBlocks
                    });
                    moves++;
                } catch (moveError) {
                    errors++;
                    log('follow move: ' + moveError.message);
                }
            }
        }
    }

    if (autoPickup) {
        var after = drone.state();
        if (after && after.isFull !== true) {
            try {
                var vacuum = drone.suckItems({radius: 6});
                if (vacuum && vacuum.sucked) picked += vacuum.sucked;
            } catch (pickupError) {
                log('follow pickup: ' + pickupError.message);
            }
        }
    }

    var left = deadline - Date.now();
    if (left <= 0) break;
    sleep(Math.min(2000, left));
}

var finalState = drone.state();
var position = null;
if (finalState) position = {x: finalState.x, y: finalState.y, z: finalState.z};
return {
    ok: true,
    followedSeconds: seconds,
    player: first.name || playerName || '',
    moves: moves,
    heldWhileAimed: held,
    pickedUp: picked,
    errors: errors,
    anchor: anchor,
    position: position,
    note: 'look at the drone and it holds still; walk away and it repositions beside you'
};
