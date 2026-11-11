//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Chest sorting. params: { radius, max_moves, dry_run }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var radius = clamp(cfg.radius || LIMITS.scanRadiusDefault, LIMITS.chestRadiusMin, LIMITS.scanRadiusMax);
var maxMoves = clamp(cfg.max_moves || 48, 1, SKILL_LIMITS.largeScanLimit);

function categoryOf(name) {
    var n = String(name || '').toLowerCase();
    if (/ore|ingot|dust|gem|nugget|coal|diamond|emerald|redstone|lapis|quartz|crystal/.test(n)) return 'ores';
    if (/seed|wheat|carrot|potato|apple|bread|beef|pork|chicken|fish|melon|pumpkin|sugar|cane|egg|food|cake|cookie/.test(n)) return 'food';
    if (/sword|pickaxe|shovel|hoe|helmet|chestplate|leggings|boots|bow|arrow|shield|tool/.test(n)) return 'gear';
    if (/redstone|piston|lever|button|repeater|comparator|torch|circuit|chip|capacitor|transistor|lamp/.test(n)) return 'redstone';
    if (/log|plank|stone|dirt|sand|gravel|cobble|brick|glass|wool|clay|concrete|terracotta|block/.test(n)) return 'blocks';
    return 'misc';
}

var scan = drone.scanChests({filter: 'all', radius: radius});
var chests = (scan && scan.chests) || [];
if (chests.length < 2) {
    return {
        ok: false, error: 'need at least two chests to sort',
        found: chests.length,
        hint: 'place more chests, then name the categories by putting one example item in each'
    };
}

var snapshots = [];
for (var c = 0; c < chests.length; c++) {
    var chest = chests[c];
    var contents = drone.getChest({x: chest.x, y: chest.y, z: chest.z});
    var items = (contents && contents.items) || [];
    var votes = {};
    var counts = {};
    for (var i = 0; i < items.length; i++) {
        var cat = categoryOf(items[i].name);
        votes[cat] = (votes[cat] || 0) + 1;
        counts[items[i].name] = (counts[items[i].name] || 0) + (items[i].count || 1);
    }
    var bestCat = 'misc';
    var bestVotes = -1;
    for (var key in votes) {
        if (votes[key] > bestVotes) {
            bestVotes = votes[key];
            bestCat = key;
        }
    }
    snapshots.push({
        chest: {x: chest.x, y: chest.y, z: chest.z},
        items: items,
        free: (contents && contents.freeSlots) || 0,
        category: bestCat,
        isUnsorted: Object.keys(votes).length > 2 || (items.length > 0 && bestVotes <= items.length / 3)
    });
}

var moves = [];
for (var s = 0; s < snapshots.length; s++) {
    var source = snapshots[s];
    for (var m = 0; m < source.items.length && moves.length < maxMoves; m++) {
        var item = source.items[m];
        var itemCat = categoryOf(item.name);
        if (!source.isUnsorted && itemCat === source.category) continue;
        var target = null;
        for (var d = 0; d < snapshots.length; d++) {
            var candidate = snapshots[d];
            if (candidate === source) continue;
            if (candidate.category !== itemCat) continue;
            if (candidate.free <= 0) continue;
            target = candidate;
            break;
        }
        if (!target) continue;
        target.free -= 1;
        moves.push({
            from: source.chest, to: target.chest, item: item,
            category: itemCat
        });
    }
}

if (!moves.length) {
    return {
        ok: true, moved: 0, chests: snapshots.length,
        note: 'storage is already grouped; no matching destination chests were needed'
    };
}
if (cfg.dry_run === true) {
    return {
        ok: true, dryRun: true, plannedMoves: moves.length,
        moves: moves.slice(0, 40), chests: snapshots.length
    };
}

var moved = 0;
var failed = 0;
var byCategory = {};
for (var mv = 0; mv < moves.length; mv++) {
    var move = moves[mv];
    try {
        var result = drone.moveItem({
            from: 'chest', to: 'chest',
            filter: move.item.name,
            count: move.item.count || 64,
            from_x: move.from.x, from_y: move.from.y, from_z: move.from.z,
            to_x: move.to.x, to_y: move.to.y, to_z: move.to.z
        });
        var amount = (result && result.moved) || 0;
        if (amount > 0) {
            moved += amount;
            byCategory[move.category] = (byCategory[move.category] || 0) + amount;
        } else {
            failed++;
        }
    } catch (moveError) {
        failed++;
        log('sort move failed: ' + moveError.message);
    }
}

return {
    ok: moved > 0, moved: moved, failed: failed, byCategory: byCategory,
    chests: snapshots.length, plannedMoves: moves.length
};
