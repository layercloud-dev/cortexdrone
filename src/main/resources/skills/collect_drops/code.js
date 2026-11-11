//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Drop pickup. params: { radius, filter, max_clusters }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var radius = clamp(cfg.radius || SKILL_LIMITS.mediumRadiusDefault, LIMITS.dropRadiusMin, LIMITS.scanRadiusMax);
var filter = cfg.filter || 'all';
var maxClusters = clamp(cfg.max_clusters || 12, 1, SKILL_LIMITS.clusterCap32);

var drops = drone.getDrops({radius: radius, filter: filter});
if (!drops || !drops.count) {
    return {ok: true, collected: 0, clusters: 0, note: 'no matching drops nearby'};
}

var visited = {};
var clusters = 0;
var list = drops.drops || [];
for (var i = 0; i < list.length && clusters < maxClusters; i++) {
    var drop = list[i];
    var key = Math.round(drop.x) + ',' + Math.round(drop.z);
    if (visited[key]) continue;
    visited[key] = true;
    clusters++;
    try {
        drone.goto({x: drop.x, y: drop.y + 1, z: drop.z, allow_dig: false});
        drone.suckItems({radius: 4});
    } catch (pickupError) {
        log('pickup skipped at ' + key + ': ' + pickupError.message);
    }
}

var state = drone.state();
return {
    ok: true,
    collected: drops.count,
    clusters: clusters,
    inventoryItems: state.totalItems
};
