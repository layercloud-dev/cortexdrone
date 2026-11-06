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
