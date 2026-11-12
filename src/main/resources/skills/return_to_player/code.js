//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Return to the task player, preferring the remembered no-dig route.
// params: { player, allow_dig, escape_cave, max_wait_ms }
//
// The live log contains two opposite failures for this skill:
//   - a direct goto to a player in the covered base chamber timed out because
//     the only opening is the vertical shaft;
//   - a direct goto from the mining cave to the base tunnelled through the
//     chamber, which the owner explicitly forbade.
// The route below is therefore: direct -> remembered shaft/exit point ->
// open a 2x2 shaft only when the drone is genuinely underground far from the
// protected place -> report the failure instead of guessing.
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var player = drone.getPlayer(cfg.player ? {name: cfg.player} : {});
if (!player || player.ok === false) {
    return {ok: false, error: 'player is offline or unavailable'};
}

var state = drone.state();
if (!state || state.ok === false) {
    return {ok: false, error: 'no live drone state'};
}

var attempts = [];
function note(label, dest, result) {
    attempts.push({
        label: label,
        to: [Math.round(dest.x), Math.round(dest.y), Math.round(dest.z)],
        arrived: !!(result && result.arrived),
        error: result && result.error ? result.error : ''
    });
    return !!(result && result.arrived && result.arrived !== false);
}

function fly(dest, allowDig, label) {
    try {
        var result = drone.goto({
            x: dest.x, y: dest.y, z: dest.z,
            allow_dig: allowDig === true,
            max_wait_ms: cfg.max_wait_ms || LIMITS.maxWaitMs,
            recovery_attempts: LIMITS.recoveryAttempts
        });
        return note(label, dest, result);
    } catch (navError) {
        note(label, dest, {arrived: false, error: navError.message});
        return false;
    }
}

function arrived() {
    return {
        ok: true,
        arrived: true,
        player: player.name || '',
        position: drone.state(),
        attempts: attempts,
        route: cfg.allow_dig === true ? 'direct-with-dig' : 'no-dig'
    };
}

// 1. The common case: open air or an already reachable chamber.
if (fly({x: player.blockX, y: player.blockY + 2, z: player.blockZ},
        cfg.allow_dig === true, 'direct')) {
    return arrived();
}

// 2. A remembered base/exit/shaft point. The memory entries are written by the
// player ("the exit is vertical", "go outside and remember trace"), so they are
// the only route that does not require guessing a tunnel.
if (player.canSeeSky === false || player.underground === true) {
    var memories = null;
    try {
        memories = drone.memory({
            action: 'search',
            query: 'base exit shaft surface outside route',
            limit: 8
        });
    } catch (memoryError) {
        log('memory lookup failed: ' + memoryError.message);
    }
    var list = (memories && memories.memories) ? memories.memories : [];
    var playerDim = (player.dimension !== undefined) ? player.dimension : state.dimension;
    var route = null;
    for (var i = 0; i < list.length; i++) {
        var candidate = list[i];
        if (!candidate || !candidate.hasPosition) continue;
        if (candidate.dimension !== undefined && playerDim !== undefined
            && candidate.dimension !== playerDim) continue;
        var text = ((candidate.name || '') + ' ' + (candidate.text || '') + ' ' + (candidate.tags || '')).toLowerCase();
        if (text.indexOf('shaft') < 0 && text.indexOf('exit') < 0
            && text.indexOf('outside') < 0 && text.indexOf('surface') < 0) continue;
        route = candidate;
        break;
    }
    if (route) {
        var routeX = Math.round(route.x);
        var routeZ = Math.round(route.z);
        var aboveY = Math.max(Math.round(route.y), Math.ceil(state.y) + 1);
        if (fly({x: routeX, y: aboveY, z: routeZ}, false, 'remembered-route')) {
            // Descend the open shaft to the chamber floor, then approach the
            // player horizontally. A direct goal inside the chamber would make
            // the pathfinder aim at the roof.
            var floorY = player.blockY + 1;
            if (fly({x: routeX, y: floorY, z: routeZ}, false, 'shaft-descent')) {
                if (fly({x: player.blockX, y: player.blockY + 1, z: player.blockZ},
                        false, 'chamber-approach')) {
                    return arrived();
                }
            }
        }
    }
}

// 3. The drone may be sealed in a cave far from the base. Opening a 2x2 shaft
// is safe there (the protected-zone guard still refuses the base itself).
var top = null;
try {
    var topResult = drone.getTopY({
        x: Math.floor(state.x),
        z: Math.floor(state.z)
    });
    if (topResult && topResult.ok !== false) top = topResult.topY;
} catch (topError) {
    log('surface height unavailable: ' + topError.message);
}
if (cfg.escape_cave !== false && top !== null && state.y < top - 2) {
    try {
        var escaped = skills.call('escape_cave', {
            x: Math.floor(state.x),
            z: Math.floor(state.z),
            top_y: top,
            collect: false
        });
        attempts.push({label: 'escape_cave', ok: !!(escaped && escaped.ok),
            reachedY: escaped ? escaped.reachedY : null});
        if (escaped && escaped.ok) {
            var st2 = drone.state();
            if (fly({x: Math.floor(st2.x), y: top + 2, z: Math.floor(st2.z)},
                    true, 'surface-climb')) {
                if (fly({x: player.blockX, y: player.blockY + 2, z: player.blockZ},
                        cfg.allow_dig === true, 'direct-after-escape')) {
                    return arrived();
                }
            }
        }
    } catch (escapeError) {
        attempts.push({label: 'escape_cave', ok: false, error: escapeError.message});
    }
}

return {
    ok: false,
    arrived: false,
    player: player.name || '',
    position: drone.state(),
    attempts: attempts,
    error: 'could not reach the player without damaging a protected place',
    hint: 'If the player is inside a covered base, use the remembered vertical shaft; '
        + 'if the drone is sealed in a cave, run escape_cave first.'
};
