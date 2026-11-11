//noinspection JSAnnotator,JSUnresolvedReference,JSUnusedAssignment,JSUnusedGlobalSymbols,JSDuplicatedDeclaration,JSMismatchedCollectionQueryUpdate,UnnecessaryLocalVariableJS
// Furnace smelting at vanilla speed. params:
// { filter, count, furnace:{x,y,z}, radius, fuel, clear, wait, max_wait_s }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var filter = cfg.filter || cfg.item || null;
if (!filter) {
    return {
        ok: false, error: 'smelt_items needs filter (the item to smelt)',
        hint: 'e.g. filter:"iron_ore" or filter:"sand"'
    };
}
var wanted = clamp(cfg.count || 6, 1, SKILL_LIMITS.smallCountCap);
var radius = clamp(cfg.radius || LIMITS.scanRadiusDefault, LIMITS.blockScanRadiusMin, LIMITS.furnaceRadiusMax);
var clearInput = cfg.clear !== false;
var wait = cfg.wait !== false;
var maxWaitS = clamp(cfg.max_wait_s || (wanted * 10 + 20), SKILL_LIMITS.smeltWaitMin, SKILL_LIMITS.smeltWaitMax);

// Vanilla 1.7.10 furnace slots and burn times (ticks).
var slotInput = 0;
var slotFuel = 1;
var slotOutput = 2;
var smeltTicks = 200;

function burnTicks(name, registry) {
    var n = String(name || '').toLowerCase();
    var r = String(registry || '').toLowerCase();
    var both = n + ' ' + r;
    if (both.indexOf('lava') >= 0 && both.indexOf('bucket') >= 0) return 20000;
    if (both.indexOf('coal_block') >= 0 || n.indexOf('coal block') >= 0) return 16000;
    if (both.indexOf('blaze_rod') >= 0 || n === 'blaze rod') return 2400;
    if (both.indexOf('coal') >= 0 || n === 'charcoal') return 1600;
    if (both.indexOf('planks') >= 0 || both.indexOf('_plank') >= 0) return 300;
    if (both.indexOf('log') >= 0 || both.indexOf('wood') >= 0) {
        if (both.indexOf('slab') >= 0) return 150;
        return 300;
    }
    if (both.indexOf('slab') >= 0 && both.indexOf('wood') >= 0) return 150;
    if (both.indexOf('stick') >= 0 || both.indexOf('sapling') >= 0) return 100;
    return 0;
}

function slotItems(furnace, slot) {
    var out = [];
    var items = (furnace && furnace.items) || [];
    for (var i = 0; i < items.length; i++) {
        if (Number(items[i].slot) === slot) out.push(items[i]);
    }
    return out;
}

function slotCount(furnace, slot) {
    var items = slotItems(furnace, slot);
    var total = 0;
    for (var i = 0; i < items.length; i++) total += Number(items[i].count || 0);
    return total;
}

function slotFirst(furnace, slot) {
    var items = slotItems(furnace, slot);
    return items.length ? items[0] : null;
}

function furnaceAt(scan, x, y, z) {
    var list = (scan && scan.furnaces) || [];
    for (var i = 0; i < list.length; i++) {
        if (Number(list[i].x) === x && Number(list[i].y) === y && Number(list[i].z) === z) {
            return list[i];
        }
    }
    return null;
}

// 1) Pick a furnace with a usable output slot.
var target = cfg.furnace;
if (!target) {
    var scan = drone.scanFurnaces({radius: radius});
    var list = (scan && scan.furnaces) || [];
    var best = null;
    for (var i = 0; i < list.length; i++) {
        var f = list[i];
        var out = slotFirst(f, slotOutput);
        var input = slotFirst(f, slotInput);
        var outputFree = !out || (out.name && nameMatches(out.name, out.registryName, filter, out.oreDict));
        var inputFree = !input || nameMatches(input.name, input.registryName, filter, input.oreDict);
        if (!outputFree) continue;
        if (!inputFree && !clearInput) continue;
        if (!best || Number(f.dist) < Number(best.dist)) best = f;
    }
    if (!best) {
        return {
            ok: false, error: 'no usable furnace within ' + radius + 'm',
            hint: 'place a furnace, clear its output slot, or widen radius'
        };
    }
    target = {x: best.x, y: best.y, z: best.z};
}
var furnace = {x: Math.round(target.x), y: Math.round(target.y), z: Math.round(target.z)};

try {
    drone.goto({standoffOf: furnace, allow_dig: false, max_wait_ms: 20000});
} catch (navError) {
    log('approach failed: ' + navError.message);
}

var before = furnaceAt(drone.scanFurnaces({radius: 6}), furnace.x, furnace.y, furnace.z);
if (!before) {
    return {
        ok: false, error: 'furnace at ' + furnace.x + ',' + furnace.y + ',' + furnace.z
            + ' is not loaded or is not a furnace', furnace: furnace
    };
}

var preCollected = 0;
if (slotCount(before, slotOutput) > 0) {
    try {
        var takenOut = drone.moveItem({
            from: 'chest', to: 'drone',
            from_x: furnace.x, from_y: furnace.y, from_z: furnace.z,
            from_slot: slotOutput, to_slot: -1, count: MAX_TRANSFER_COUNT
        });
        preCollected = (takenOut && takenOut.moved) || 0;
    } catch (outError) {
        return {
            ok: false, error: 'output slot is full and could not be emptied: ' + outError.message,
            furnace: furnace, hint: 'free a drone slot, then retry'
        };
    }
}

// 2) Load the input.
var currentInput = slotFirst(before, slotInput);
if (currentInput && !nameMatches(currentInput.name, currentInput.registryName, filter, currentInput.oreDict)) {
    if (!clearInput) {
        return {
            ok: false, error: 'the furnace input slot holds ' + currentInput.name,
            furnace: furnace, hint: 'pass clear:true to move it out first'
        };
    }
    try {
        drone.moveItem({
            from: 'chest', to: 'drone',
            from_x: furnace.x, from_y: furnace.y, from_z: furnace.z,
            from_slot: slotInput, to_slot: -1, count: 64
        });
    } catch (clearError) {
        return {
            ok: false, error: 'could not clear the input slot: ' + clearError.message,
            furnace: furnace
        };
    }
}

var already = 0;
if (currentInput && nameMatches(currentInput.name, currentInput.registryName, filter, currentInput.oreDict)) {
    already = slotCount(before, slotInput);
}
var toLoad = Math.max(0, wanted - already);
var loaded = 0;
if (toLoad > 0) {
    var have = countInInventory(drone.state().inventory, filter);
    if (have <= 0) {
        return {
            ok: false, error: 'no "' + filter + '" in the drone inventory',
            furnace: furnace, hint: 'gather or withdraw it first'
        };
    }
    if (have < toLoad) {
        log('only ' + have + ' of ' + filter + ' available; smelting that many');
        toLoad = have;
    }
    try {
        var inResult = drone.moveItem({
            from: 'drone', to: 'chest',
            to_x: furnace.x, to_y: furnace.y, to_z: furnace.z,
            to_slot: slotInput, count: toLoad, filter: filter
        });
        loaded = (inResult && inResult.moved) || 0;
        // move_item stops at the first matching source stack; top up while the
        // furnace still has room and the drone still holds the item.
        var topUps = 0;
        while (loaded < toLoad && topUps < 8
        && countInInventory(drone.state().inventory, filter) > 0) {
            topUps++;
            var extra = drone.moveItem({
                from: 'drone', to: 'chest',
                to_x: furnace.x, to_y: furnace.y, to_z: furnace.z,
                to_slot: slotInput, count: toLoad - loaded, filter: filter
            });
            var gained = (extra && extra.moved) || 0;
            if (gained <= 0) break;
            loaded += gained;
        }
    } catch (inError) {
        return {
            ok: false, error: 'could not load the furnace input: ' + inError.message,
            furnace: furnace
        };
    }
    if (loaded <= 0) {
        return {
            ok: false, error: 'the furnace refused the input load',
            furnace: furnace, hint: 'the furnace may be full or unreachable'
        };
    }
}

var batch = already + loaded;
var afterLoad = furnaceAt(drone.scanFurnaces({radius: 6}), furnace.x, furnace.y, furnace.z);
var fuelInfo = null;

// 3) Fuel: one sufficient item when possible, otherwise the fewest items.
if (batch > 0) {
    var fuelSlot = slotFirst(afterLoad, slotFuel);
    var fuelSlotName = fuelSlot ? fuelSlot.name : null;
    var fuelSlotCount = slotCount(afterLoad, slotFuel);
    var fuelSlotUsable = !!fuelSlot && burnTicks(fuelSlotName, fuelSlot.registryName) > 0
        && (!cfg.fuel || nameMatches(fuelSlotName, fuelSlot.registryName, cfg.fuel, fuelSlot.oreDict));
    if (!fuelSlotUsable) {
        var inventory = drone.state().inventory || [];
        var candidates = [];
        for (var s = 0; s < inventory.length; s++) {
            var stack = inventory[s];
            if (!stack || !stack.count) continue;
            var explicit = cfg.fuel && nameMatches(stack.name, stack.registryName, cfg.fuel, stack.oreDict);
            var burn = burnTicks(stack.name, stack.registryName);
            if (burn <= 0 && !explicit) continue;
            if (burn <= 0) burn = 1600; // unknown modded fuel: assume coal-like
            var perItem = Math.floor(burn / smeltTicks);
            if (perItem <= 0) continue;
            candidates.push({
                name: stack.name, filter: stack.name, burn: burn,
                perItem: perItem, available: Number(stack.count)
            });
        }
        if (!candidates.length) {
            return {
                ok: false, error: 'no usable fuel in the drone inventory',
                furnace: furnace, loaded: loaded, batch: batch, stalled: 'fuel',
                hint: 'bring coal, charcoal, planks, logs or blaze rods'
            };
        }
        var sufficient = [];
        for (var c = 0; c < candidates.length; c++) {
            if (candidates[c].perItem >= batch) sufficient.push(candidates[c]);
        }
        var pick = null;
        if (sufficient.length) {
            pick = sufficient[0];
            for (var s2 = 1; s2 < sufficient.length; s2++) {
                if (sufficient[s2].burn < pick.burn) pick = sufficient[s2];
            }
        } else {
            pick = candidates[0];
            for (var c2 = 1; c2 < candidates.length; c2++) {
                if (candidates[c2].perItem > pick.perItem) pick = candidates[c2];
            }
        }
        var need = Math.max(1, Math.ceil(batch / pick.perItem));
        need = Math.min(need, pick.available, 64);
        if (fuelSlot && fuelSlotName) {
            try {
                drone.moveItem({
                    from: 'chest', to: 'drone',
                    from_x: furnace.x, from_y: furnace.y, from_z: furnace.z,
                    from_slot: slotFuel, to_slot: -1, count: 64
                });
            } catch (fuelClearError) {
                log('could not clear the fuel slot: ' + fuelClearError.message);
            }
        }
        try {
            var fuelResult = drone.moveItem({
                from: 'drone', to: 'chest',
                to_x: furnace.x, to_y: furnace.y, to_z: furnace.z,
                to_slot: slotFuel, count: need, filter: pick.filter
            });
            fuelInfo = {
                name: pick.filter, used: (fuelResult && fuelResult.moved) || 0,
                ticksEach: pick.burn, itemsPerFuel: pick.perItem
            };
        } catch (fuelError) {
            return {
                ok: false, error: 'could not load fuel: ' + fuelError.message,
                furnace: furnace, loaded: loaded
            };
        }
    } else {
        fuelInfo = {name: fuelSlotName, used: 0, alreadyThere: fuelSlotCount};
    }
}

if (!wait) {
    return {
        ok: true, loaded: loaded, batch: batch, furnace: furnace,
        fuel: fuelInfo, preCollected: preCollected,
        note: 'input and fuel loaded; the world will smelt it'
    };
}

// 4) Wait for the world. The mod never advances cook time itself.
var deadline = Date.now() + maxWaitS * 1000;
var waited = 0;
var stalled = '';
var finalScan = null;
while (Date.now() < deadline) {
    var now = furnaceAt(drone.scanFurnaces({radius: 6}), furnace.x, furnace.y, furnace.z);
    if (!now) {
        stalled = 'unloaded';
        break;
    }
    finalScan = now;
    if (slotCount(now, slotOutput) >= batch) break;
    var inputLeft = slotCount(now, slotInput);
    var fuelLeft = slotCount(now, slotFuel);
    var burning = Number(now.burnTime || 0) > 0;
    if (inputLeft <= 0) break;
    if (!burning && fuelLeft <= 0) {
        stalled = 'fuel';
        break;
    }
    sleep(4000);
    waited += 4;
}
if (!stalled && Date.now() >= deadline) stalled = 'timeout';

if (finalScan) {
    // Re-read once more so the reported output slot is post-completion.
    var latest = furnaceAt(drone.scanFurnaces({radius: 6}), furnace.x, furnace.y, furnace.z);
    if (latest) finalScan = latest;
}

// 5) Collect the output plus anything left in the output slot.
var collected = 0;
try {
    var outResult = drone.moveItem({
        from: 'chest', to: 'drone',
        from_x: furnace.x, from_y: furnace.y, from_z: furnace.z,
        from_slot: slotOutput, to_slot: -1, count: MAX_TRANSFER_COUNT
    });
    collected = (outResult && outResult.moved) || 0;
} catch (collectError) {
    log('output withdraw failed: ' + collectError.message);
}

var state = drone.state();
return {
    ok: collected > 0,
    smelted: collected,
    batch: batch,
    loaded: loaded,
    furnace: furnace,
    preCollected: preCollected,
    fuel: fuelInfo,
    inputLeft: finalScan ? slotCount(finalScan, slotInput) : null,
    waitedSeconds: waited,
    stalled: stalled,
    outputName: finalScan && slotFirst(finalScan, slotOutput)
        ? slotFirst(finalScan, slotOutput).name : null,
    inventoryItems: state && state.totalItems,
    hint: stalled === 'fuel'
        ? 'the furnace ran out of usable fuel; add coal/charcoal/planks and retry'
        : (stalled === 'timeout'
            ? 'batch still cooking; the remaining items are in the furnace, wait and withdraw again'
            : '')
};
