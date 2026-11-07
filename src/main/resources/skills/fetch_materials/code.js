// Batched material restock. params: { materials:[{filter,count}] | {name:count} | [names], count, radius }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var raw = cfg.materials || cfg.items || cfg.list;
var defaultCount = clamp(cfg.count || 64, 1, 2304);
var radius = clamp(cfg.radius || 32, 4, 64);

var entries = [];
if (!raw) {
  return { ok: false, error: 'fetch_materials needs materials',
    hint: 'pass materials:[{filter:"cobblestone",count:128}] or {cobblestone:128}' };
}
var isList = (typeof Array.isArray === 'function' && Array.isArray(raw))
  || Object.prototype.toString.call(raw) === '[object Array]';
if (isList) {
  for (var i = 0; i < raw.length; i++) {
    var item = raw[i];
    if (typeof item === 'string') {
      entries.push({ filter: item, count: defaultCount });
    } else if (item && (item.filter || item.item)) {
      entries.push({ filter: item.filter || item.item,
        count: clamp(item.count || defaultCount, 1, 2304) });
    }
  }
} else if (typeof raw === 'object') {
  for (var key in raw) {
    if (!Object.prototype.hasOwnProperty.call(raw, key)) continue;
    entries.push({ filter: key, count: clamp(raw[key] || defaultCount, 1, 2304) });
  }
}

if (!entries.length) {
  return { ok: false, error: 'no usable material entries',
    hint: 'each entry needs a filter name' };
}

var results = [];
var blockers = [];
var totalFetched = 0;
var satisfied = 0;
for (var e = 0; e < entries.length; e++) {
  var request = entries[e];
  var result = null;
  try {
    result = skills.call('withdraw_items', { filter: request.filter,
      count: request.count, radius: radius });
  } catch (withdrawError) {
    result = { ok: false, error: withdrawError.message };
  }
  var have = countInInventory(drone.state().inventory, request.filter);
  var entry = { filter: request.filter, wanted: request.count,
    got: (result && result.withdrawn) || 0, have: have,
    source: result && result.sources ? result.sources : [] };
  totalFetched += entry.got;
  results.push(entry);
  if (have >= entry.wanted) {
    satisfied++;
  } else {
    blockers.push({ filter: request.filter,
      have: have, wanted: entry.wanted,
      hint: result && result.hint ? result.hint
        : (result && result.error ? result.error : 'not found nearby') });
  }
}

var state = drone.state();
return {
  ok: satisfied > 0,
  requested: entries.length,
  satisfied: satisfied,
  fetched: results,
  totalFetched: totalFetched,
  blockers: blockers,
  inventoryItems: state && state.totalItems,
  hint: blockers.length
    ? 'short on: ' + blockers.map(function (b) { return b.filter; }).join(', ')
    : ''
};
