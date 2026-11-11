//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Kill one entity with certainty. params: { entity_id|entity, item, slot,
// max_attacks, max_seconds, collect }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var id = pick(cfg.entity_id, cfg.entityId, cfg.entity ? cfg.entity.id : null);
if (id === null || id === undefined || id < 0) {
    return {
        ok: false, killed: false, entity_id: id,
        blocker: 'entity_id is required; resolve it with drone.getEntities first'
    };
}
var item = pick(cfg.item, null);
var slot = pick(cfg.slot, null);
var maxAttacks = clamp(cfg.max_attacks || 60, 1, SKILL_LIMITS.attackCap200);
var maxSeconds = clamp(cfg.max_seconds || 60, 1, SKILL_LIMITS.attackSecondsMax);
var started = Date.now();
var deadline = started + maxSeconds * 1000;

// Tool errors carry a JSON `toolResult`; read it instead of guessing.
function failureOf(error) {
    var raw = error && error.toolResult;
    if (!raw) return null;
    try {
        var parsed = JSON.parse(raw);
        return parsed && parsed.ok === false ? parsed : null;
    } catch (parseError) {
        return null;
    }
}

function planFor() {
    var query = {entity_id: id};
    if (item) query.item = item;
    if (slot !== null && slot !== undefined) query.slot = slot;
    try {
        return drone.attackPlan(query);
    } catch (planError) {
        var failed = failureOf(planError);
        return failed || {ok: false, error: 'attack_plan failed'};
    }
}

function swing() {
    var args = {entity_id: id};
    if (item) args.item = item;
    if (slot !== null && slot !== undefined) args.slot = slot;
    return drone.attackEntity(args);
}

var plan = planFor();
if (plan.ok === false) {
    return {
        ok: false, killed: false, entity_id: id, name: plan.name,
        blocker: plan.error || 'target not found'
    };
}
if (plan.player) {
    return {
        ok: false, killed: false, entity_id: id, name: plan.name,
        blocker: 'refusing to attack a player'
    };
}
if (!plan.can_damage) {
    return {
        ok: false, killed: false, entity_id: id, name: plan.name,
        blocker: plan.blocker || 'target cannot be damaged by this weapon'
    };
}

var before = plan.total_health;
var after = before;
var waitMs = plan.next_attack_in_ms || 0;
var attacks = 0;
var misses = 0;
var killed = false;

// A target already inside its hurt-resistance window ignores a hit right now.
if (plan.immune === true && waitMs > 0) sleep(waitMs);

while (attacks < maxAttacks && Date.now() < deadline) {
    if (attacks > 0 && waitMs > 0) sleep(waitMs);
    var hit;
    try {
        hit = swing();
    } catch (attackError) {
        var alive = planFor();
        if (alive.ok !== false && (!alive.alive || alive.total_health <= 0)) {
            killed = true;
            after = 0;
            break;
        }
        if (alive.ok === false) {
            return {
                ok: false, killed: false, entity_id: id, name: plan.name, attacks: attacks,
                blocker: 'target disappeared before the hit was confirmed'
            };
        }
        var retry = failureOf(attackError);
        waitMs = retry && retry.retryAfterMs ? retry.retryAfterMs : 250;
        continue;
    }
    attacks++;
    if (hit.killed) {
        killed = true;
        after = 0;
        break;
    }
    after = hit.healthAfter;
    waitMs = hit.nextAttackInMs || 500;
    misses = hit.damageDealt > 0 ? 0 : misses + 1;
    if (misses < 3) continue;
    // Three swings dealt nothing: re-plan, because armor, absorption or a
    // replaced weapon can change both the hit count and the safe cadence.
    var fresh = planFor();
    if (fresh.ok === false) {
        return {
            ok: false, killed: false, entity_id: id, name: plan.name, attacks: attacks,
            blocker: 'target disappeared before the kill was confirmed'
        };
    }
    if (!fresh.alive || fresh.total_health <= 0) {
        killed = true;
        after = 0;
        break;
    }
    if (!fresh.can_damage) {
        return {
            ok: false, killed: false, entity_id: id, name: fresh.name, attacks: attacks,
            blocker: fresh.blocker || 'damage fully absorbed'
        };
    }
    waitMs = Math.max(waitMs, fresh.next_attack_in_ms || 500);
    misses = 0;
}

if (!killed && after <= 0) killed = true;
if (killed && cfg.collect === true) {
    try {
        drone.suckItems({radius: 6});
    } catch (pickupError) {
    }
}
return {
    ok: killed,
    killed: killed,
    entity_id: id,
    name: plan.name,
    attacks: attacks,
    healthBefore: before,
    healthAfter: after,
    attacksToKill: plan.attacks_to_kill,
    damagePerHit: plan.damage_per_hit,
    seconds: Math.round((Date.now() - started) / 100) / 10,
    blocker: killed ? '' : 'attack budget or time expired before the target died'
};
