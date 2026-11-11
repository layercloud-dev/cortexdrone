//noinspection JSUnresolvedReference,JSUnusedGlobalSymbols
/*
 * CortexDrone JS runtime helpers.
 *
 * This file is evaluated in every fresh Rhino scope before the model script.
 * Keep it ES5-compatible: Rhino 1.7.15 supports let/const/arrow/template
 * literals, but the helpers themselves are intentionally conservative.
 */

function hsvToRgb(hDeg, s, v) {
    var h = ((Number(hDeg) % 360) + 360) % 360;
    var saturation = (s === undefined || s === null) ? 1 : Number(s);
    var value = (v === undefined || v === null) ? 1 : Number(v);
    var c = value * saturation;
    var x = c * (1 - Math.abs(((h / 60) % 2) - 1));
    var m = value - c;
    var r1 = 0, g1 = 0, b1 = 0;
    if (h < 60) {
        r1 = c;
        g1 = x;
    } else if (h < 120) {
        r1 = x;
        g1 = c;
    } else if (h < 180) {
        g1 = c;
        b1 = x;
    } else if (h < 240) {
        g1 = x;
        b1 = c;
    } else if (h < 300) {
        r1 = x;
        b1 = c;
    } else {
        r1 = c;
        b1 = x;
    }
    var r = Math.max(0, Math.min(255, Math.round((r1 + m) * 255)));
    var g = Math.max(0, Math.min(255, Math.round((g1 + m) * 255)));
    var b = Math.max(0, Math.min(255, Math.round((b1 + m) * 255)));
    return (r << 16) | (g << 8) | b;
}

/* Returns the first non-null/undefined value. */
function pick() {
    for (var i = 0; i < arguments.length; i++) {
        if (arguments[i] !== undefined && arguments[i] !== null) return arguments[i];
    }
    return null;
}

/*
 * Largest item count one transfer call may move (36 stacks).
 * Mirrors InventoryLimits.MAX_TRANSFER_COUNT in Java; do not restate the number
 * in a skill.
 */
var MAX_TRANSFER_COUNT = 2304;

/*
 * Bounds mirrored from the Java policy classes. Keep these the only copies on
 * the script side: an agreement test pins them to WorldBounds / ScanLimits /
 * InventoryLimits, so a Java change fails the build instead of silently
 * diverging from a shipped skill.
 */
var LIMITS = {
    footprintMin: 3,      /* WorldBounds.MIN_PLAN_SIDE */
    footprintMax: 19,     /* WorldBounds.MAX_PLAN_SIDE */
    planHeightMin: 2,     /* WorldBounds.MIN_PLAN_HEIGHT */
    planHeightMax: 12,    /* WorldBounds.MAX_PLAN_HEIGHT */
    siteSideMin: 3,       /* WorldBounds.MIN_PLAN_SIDE */
    siteSideMax: 33,      /* WorldBounds.MAX_SITE_SIDE */
    dimensionMin: 1,      /* generic minimum for a width/length argument */
    chestRadiusMin: 8,    /* skill-level floor for container searches */
    collectRadiusMax: 16, /* skill cap for drop collection */
    blockScanRadiusMin: 1, /* ScanLimits.MIN_RADIUS (schema floor) */
    scanRadiusDefault: 24, /* ScanLimits.DEFAULT_RADIUS */
    smallScanRadiusMax: 32, /* skill cap, well under ScanLimits.MAX_RADIUS */
    mediumRadiusMax: 48,    /* skill cap, under ScanLimits.MAX_ENTITY_RADIUS */
    scanRadiusMax: 64,    /* ScanLimits.MAX_RADIUS */
    furnaceRadiusMax: 36, /* InventoryLimits.MAX_FURNACE_RADIUS */
    dropRadiusMin: 2,     /* ScanLimits.MIN_DROP_RADIUS */
    distantRadiusMin: 64,     /* ScanLimits.MIN_DISTANT_RADIUS */
    distantRadiusMax: 1500,   /* ScanLimits.MAX_DISTANT_RADIUS */
    distantRadiusDefault: 1200, /* ScanLimits.DEFAULT_DISTANT_RADIUS */
    minDigBlocks: 0,      /* NavigationLimits.MIN_DIG_BLOCKS */
    maxDigBlocks: 512,    /* NavigationLimits.MAX_DIG_BLOCKS */
    maxWaitMs: 180000,    /* NavigationLimits.MAX_WAIT_MS */
    recoveryAttempts: 12, /* NavigationLimits.DEFAULT_RECOVERY_ATTEMPTS */
    inventoryRadiusDefault: 32, /* InventoryLimits.DEFAULT_RADIUS */
    chestRadiusDefault: 28,     /* InventoryLimits.DEFAULT_CHEST_RADIUS */
    roomRadiusDefault: 18,      /* ScanLimits.DEFAULT_ROOM_RADIUS */
    landingRadiusDefault: 12,   /* LandingScanner.DEFAULT_RADIUS */
    landingRadiusMax: 24, /* LandingScanner.MAX_RADIUS */
    landingY: 250,        /* WorldBounds.MAX_LANDING_Y */
    defaultTransferCount: 512, /* InventoryLimits.DEFAULT_TRANSFER_COUNT */
    defaultDigBlocks: 128,     /* NavigationLimits.DEFAULT_DIG_BLOCKS */
    maxCoordinate: 30000000    /* WorldBounds.MAX_COORDINATE */
};

/*
 * Task-local tuning for shipped skills. These are not Java policy mirrors;
 * they live here so a repeated cap has one owner on the script side.
 */
var SKILL_LIMITS = {
    smallScanLimit: 96,
    largeScanLimit: 128,
    farmScanLimit: 400,
    cactusScanLimit: 800,
    lightRadiusMin: 2,
    lightRadiusMax: 16,
    lightRadiusDefault: 8,
    mediumRadiusDefault: 16,
    chopRadiusDefault: 20,
    shortRadiusDefault: 12,
    smallLimitDefault: 32,
    largeLimitDefault: 48,
    smallCountCap: 64,
    attackCap200: 200,
    budget256: 256,
    budget800: 800,
    budget1200: 1200,
    budget1024: 1024,
    smallMax16: 16,
    smallBudgetMin: 8,
    scanLimit192: 192,
    budget1600: 1600,
    craftBatch: 256,
    guardDurationDefault: 120,
    guardDurationMin: 10,
    huntDurationMin: 15,
    followDurationMin: 5,
    durationMax: 240,
    killCap32: 32,
    maxClusters: 10,
    maxClustersLarge: 12,
    maxClustersSmall: 8,
    maxClustersTiny: 4,
    attackSecondsMax: 180,
    wallHeightMin: 2,
    wallHeightMax: 8,
    cactusKeepMax: 3,
    clusterCap32: 32,
    shaftMax: 3,
    guardTargetsMax: 16,
    guardSecondsMin: 3,
    guardSecondsMax: 120,
    lightSpacingMin: 2,
    lightSpacingMax: 8,
    patrolLoopsMax: 6,
    clearCapMax: 900,
    buildFailuresMax: 60,
    rainbowStepsMin: 6,
    rainbowStepsMax: 120,
    rainbowDelayMax: 1000,
    smeltWaitMin: 5,
    smeltWaitMax: 300,
    branchSpacingMin: 2,
    branchSpacingMax: 24,
    branchLengthMax: 32,
    surveyHeightMax: 24
};

/* Clamps a number into [min, max]. */
function clamp(value, min, max) {
    var n = Number(value);
    if (isNaN(n)) n = min;
    return Math.max(min, Math.min(max, n));
}

/* Compact distance helper (Math.hypot is missing in older Rhino builds). */
function distance3(ax, ay, az, bx, by, bz) {
    var dx = ax - bx, dy = ay - by, dz = az - bz;
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
}

/* Safe optional property access used by the source preprocessor. */

//noinspection JSUnusedGlobalSymbols
function __opt(object, key) {
    return (object === null || object === undefined) ? undefined : object[key];
}

/* Safe optional index access used by the source preprocessor. */

//noinspection JSUnusedGlobalSymbols
function __optIndex(object, key) {
    return (object === null || object === undefined) ? undefined : object[key];
}

/* Small readable summary of an inventory-like array. */

//noinspection JSUnusedGlobalSymbols
function summarizeItems(items, limit) {
    var max = limit || 8;
    if (!items) return "empty";
    var out = [];
    for (var i = 0; i < items.length && out.length < max; i++) {
        var item = items[i];
        if (!item) continue;
        //noinspection JSUnresolvedReference
        out.push((item.name || item.id || "item") + " x" + (item.count || item.stackSize || 1));
    }
    return out.length ? out.join(", ") : "empty";
}

/* Lowercase word tokens of a display name, registry name or phrase. */
function __words(text) {
    var raw = String(text === undefined || text === null ? "" : text).toLowerCase();
    var parts = raw.split(/[^a-z0-9]+/);
    var out = [];
    for (var i = 0; i < parts.length; i++) {
        if (parts[i].length > 0) out.push(parts[i]);
    }
    return out;
}

/* Trims a trailing plural "s" so "ores" matches "Iron Ore". */
function __singular(token) {
    if (!token || token.length <= 3) return token;
    if (token.slice(-3) === "ies" && token.length > 4) return token.slice(0, -3) + "y";
    if (token.slice(-1) === "s" && token.slice(-2) !== "ss"
        && token !== "glass" && token !== "grass") {
        return token.slice(0, -1);
    }
    return token;
}

/*
 * Loose item/block name matcher used by skills.
 * Supports "|" alternatives and "!" exclusion. A filter matches when every
 * token of one alternative is present in the display name or registry name.
 *
 * OreDictionary-aware:
 *   - "#oreTin", "oredict:oreTin", "dict:plankWood", "tag:..." match the tag list;
 *   - a bare tag name ("oreIron", "ingotTin") matches that tag;
 *   - a plain word ("tin") also matches a tag that contains it as a whole token
 *     (oreTin, ingotTin), so players can ask for "tin" rather than a tag.
 * Pass the stack's `oreDict` array as the third argument when available.
 */
function nameMatches(name, registry, filter, oreDict) {
    if (filter === undefined || filter === null) return true;
    var f = String(filter).trim().toLowerCase();
    if (f === "" || f === "all" || f === "any" || f === "*") return true;
    var combined = __words(name).concat(__words(registry));
    var tags = [];
    if (oreDict && oreDict.length) {
        for (var g = 0; g < oreDict.length; g++) {
            if (oreDict[g]) tags.push(String(oreDict[g]));
        }
        // Tag words are searchable too, so "oreIron" style queries find them.
        for (var h = 0; h < tags.length; h++) {
            combined = combined.concat(__tagWords(tags[h]));
        }
    }
    var clauses = f.split("|");
    for (var i = 0; i < clauses.length; i++) {
        var clause = clauses[i].trim();
        if (!clause) continue;
        var negated = false;
        if (clause.charAt(0) === "!") {
            negated = true;
            clause = clause.slice(1).trim();
        }
        var explicitTag = false;
        var prefixes = ["#", "oredict:", "ore_dict:", "ore-dict:", "dict:", "tag:"];
        for (var p = 0; p < prefixes.length; p++) {
            if (clause.indexOf(prefixes[p]) === 0) {
                explicitTag = true;
                clause = clause.slice(prefixes[p].length).trim();
                break;
            }
        }
        if (explicitTag) {
            if (__tagMatches(tags, clause)) return !negated;
            continue;
        }
        // A bare phrase also matches a registered tag: "tin" -> oreTin/ingotTin,
        // "oreIron" -> oreIron. Falls through to display/registry tokens.
        if (__tagMatches(tags, clause)) return !negated;
        var tokens = __words(clause);
        if (!tokens.length) continue;
        var all = true;
        for (var t = 0; t < tokens.length; t++) {
            var want = __singular(tokens[t]);
            var found = false;
            for (var c = 0; c < combined.length; c++) {
                if (__singular(combined[c]) === want) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                all = false;
                break;
            }
        }
        if (all) return !negated;
    }
    return false;
}

/* True when one of the stack's OreDictionary tags matches a tag phrase. */
function __tagMatches(tags, phrase) {
    if (!tags || !tags.length) return false;
    var want = String(phrase).toLowerCase().replace(/[^a-z0-9]/g, "");
    var words = __words(phrase);
    for (var i = 0; i < tags.length; i++) {
        var original = String(tags[i]);
        if (original.toLowerCase().replace(/[^a-z0-9]/g, "") === want) return true;
        // Split camelCase before lowercasing: "oreTin" -> ore + tin.
        var have = __tagWords(original);
        var all = words.length > 0;
        for (var w = 0; w < words.length; w++) {
            var found = false;
            for (var h = 0; h < have.length; h++) {
                if (__singular(have[h]) === __singular(words[w])) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                all = false;
                break;
            }
        }
        if (all) return true;
    }
    return false;
}

/*
 * Splits an OreDictionary tag into words. Tags are camelCase
 * ("oreIron" -> ore, iron), which __words alone does not split.
 */
function __tagWords(tag) {
    var raw = String(tag === undefined || tag === null ? "" : tag);
    var spaced = raw.replace(/([a-z0-9])([A-Z])/g, "$1 $2")
        .replace(/([A-Z]+)([A-Z][a-z])/g, "$1 $2");
    return __words(spaced);
}

/* Total item count in a `state()` inventory array matching a filter. */
function countInInventory(inventory, filter) {
    var total = 0;
    if (!inventory) return total;
    for (var i = 0; i < inventory.length; i++) {
        var stack = inventory[i];
        if (!stack) continue;
        //noinspection JSUnresolvedReference
        if (nameMatches(stack.name, stack.registryName || stack.item, filter, stack.oreDict)) {
            total += Number(stack.count || 0);
        }
    }
    return total;
}

/* Small 3D coordinate key for de-duplicating build placements. */

//noinspection JSUnusedGlobalSymbols
function key3(x, y, z) {
    return Math.round(x) + "," + Math.round(y) + "," + Math.round(z);
}
