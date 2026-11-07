// Ore -> ingots -> delivery. params: { ore, count, ingot, furnace, radius, fuel, deliver, player }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var ore = cfg.ore || cfg.filter || 'iron_ore';
var count = clamp(cfg.count || 8, 1, 64);
var ingot = cfg.ingot || cfg.output || null;

if (!ingot) {
  var lower = String(ore).toLowerCase();
  if (lower.indexOf('_ore') >= 0) ingot = lower.replace(/_ore/g, '_ingot');
  else if (lower.indexOf(' ore') >= 0) ingot = lower.replace(/ ore/g, ' ingot');
  else ingot = ore;
}

var withdrawn = skills.call('withdraw_items', { filter: ore, count: count,
  radius: cfg.radius || 32 });
var held = countInInventory(drone.state().inventory, ore);
if (held <= 0) {
  return { ok: false, error: 'no ' + ore + ' available in the drone, the player or nearby chests',
    withdraw: withdrawn,
    hint: 'mine it first with mine_blocks, or widen the chest radius' };
}
if (held < count) count = held;

var smelt = skills.call('smelt_items', { filter: ore, count: count,
  furnace: cfg.furnace, radius: cfg.radius || 24, fuel: cfg.fuel,
  max_wait_s: cfg.max_wait_s || (count * 10 + 25) });
if (!smelt || smelt.ok === false) {
  return { ok: false, error: 'smelting failed: ' + (smelt && smelt.error ? smelt.error : 'unknown'),
    withdrawn: withdrawn, smelt: smelt };
}

var delivery = null;
if (cfg.deliver !== false && smelt.smelted > 0) {
  delivery = skills.call('deliver_items', { filter: ingot, count: 2304,
    player: cfg.player });
}

return {
  ok: smelt.smelted > 0,
  ore: ore,
  count: count,
  withdrawn: withdrawn && withdrawn.withdrawn,
  smelted: smelt.smelted,
  ingot: ingot,
  outputName: smelt.outputName,
  furnace: smelt.furnace,
  fuel: smelt.fuel,
  delivered: delivery && delivery.given,
  stalled: smelt.stalled,
  smelt: smelt,
  hint: smelt.smelted > 0 ? '' : 'the ore reached the furnace but nothing came out; check fuel'
};
