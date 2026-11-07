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
  if (h < 60) { r1 = c; g1 = x; }
  else if (h < 120) { r1 = x; g1 = c; }
  else if (h < 180) { g1 = c; b1 = x; }
  else if (h < 240) { g1 = x; b1 = c; }
  else if (h < 300) { r1 = x; b1 = c; }
  else { r1 = c; b1 = x; }
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
function __opt(object, key) {
  return (object === null || object === undefined) ? undefined : object[key];
}

/* Safe optional index access used by the source preprocessor. */
function __optIndex(object, key) {
  return (object === null || object === undefined) ? undefined : object[key];
}

/* Small readable summary of an inventory-like array. */
function summarizeItems(items, limit) {
  var max = limit || 8;
  if (!items) return "empty";
  var out = [];
  for (var i = 0; i < items.length && out.length < max; i++) {
    var item = items[i];
    if (!item) continue;
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
 */
function nameMatches(name, registry, filter) {
  if (filter === undefined || filter === null) return true;
  var f = String(filter).trim().toLowerCase();
  if (f === "" || f === "all" || f === "any" || f === "*") return true;
  var combined = __words(name).concat(__words(registry));
  var clauses = f.split("|");
  for (var i = 0; i < clauses.length; i++) {
    var clause = clauses[i].trim();
    if (!clause) continue;
    var negated = false;
    if (clause.charAt(0) === "!") { negated = true; clause = clause.slice(1).trim(); }
    var tokens = __words(clause);
    if (!tokens.length) continue;
    var all = true;
    for (var t = 0; t < tokens.length; t++) {
      var want = __singular(tokens[t]);
      var found = false;
      for (var c = 0; c < combined.length; c++) {
        if (__singular(combined[c]) === want) { found = true; break; }
      }
      if (!found) { all = false; break; }
    }
    if (all) return !negated;
  }
  return false;
}

/* Total item count in a `state()` inventory array matching a filter. */
function countInInventory(inventory, filter) {
  var total = 0;
  if (!inventory) return total;
  for (var i = 0; i < inventory.length; i++) {
    var stack = inventory[i];
    if (!stack) continue;
    if (nameMatches(stack.name, stack.registryName || stack.item, filter)) {
      total += Number(stack.count || 0);
    }
  }
  return total;
}

/* Small 3D coordinate key for de-duplicating build placements. */
function key3(x, y, z) {
  return Math.round(x) + "," + Math.round(y) + "," + Math.round(z);
}
