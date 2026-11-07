// Audited batch crafting. params: { filter, count, withdraw, radius, dry_run }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var filter = cfg.filter || cfg.item || cfg.result || null;
if (!filter) {
  return { ok: false, error: 'craft_items needs filter (the result item to craft)',
    hint: 'e.g. filter:"torch"' };
}
var wanted = clamp(cfg.count || 1, 1, 2304);
var withdraw = cfg.withdraw !== false;
var radius = clamp(cfg.radius || 32, 4, 64);

var recipes = drone.getRecipes({ filter: filter });
var list = (recipes && recipes.recipes) || [];
if (!list.length) {
  return { ok: false, error: 'no crafting recipe produces "' + filter + '"',
    hint: 'check the exact item name with find_item, or use get_machine_recipes for machine-only items' };
}

// Pick the recipe that yields the most per craft and needs the fewest slots.
var recipe = null;
for (var r = 0; r < list.length; r++) {
  var candidate = list[r];
  var perCraft = Math.max(1, Number(candidate.outputCount || 1));
  var slots = (candidate.ingredients || []).length;
  if (!recipe) { recipe = candidate; continue; }
  var bestPerCraft = Math.max(1, Number(recipe.outputCount || 1));
  if (perCraft > bestPerCraft
      || (perCraft === bestPerCraft && slots < (recipe.ingredients || []).length)) {
    recipe = candidate;
  }
}
if (!recipe) {
  return { ok: false, error: 'could not select a recipe for "' + filter + '"' };
}

var outputPerCraft = Math.max(1, Number(recipe.outputCount || 1));
var plannedCrafts = Math.ceil(wanted / outputPerCraft);
var names = recipe.ingredients || [];

var inventory = drone.state().inventory || [];
var audit = [];
var missing = [];
// A shaped recipe may use the same ingredient in several slots (8 cobblestone
// for a furnace), so aggregate per name before comparing with what is held.
var perCraftByName = {};
var order = [];
for (var i = 0; i < names.length; i++) {
  var name = names[i];
  if (perCraftByName[name] === undefined) {
    perCraftByName[name] = 0;
    order.push(name);
  }
  perCraftByName[name]++;
}
for (var o = 0; o < order.length; o++) {
  var item = order[o];
  var perCraft = perCraftByName[item];
  var required = plannedCrafts * perCraft;
  var have = countInInventory(inventory, item);
  var entry = { item: item, perCraft: perCraft, required: required, have: have,
    deficit: Math.max(0, required - have) };
  if (entry.deficit > 0) missing.push(entry);
  audit.push(entry);
}

if (missing.length) {
  var gathered = [];
  if (withdraw && cfg.dry_run !== true) {
    for (var m = 0; m < missing.length; m++) {
      var need = missing[m];
      try {
        var pulled = skills.call('withdraw_items', {
          filter: need.item, count: need.deficit, radius: radius });
        if (pulled && pulled.withdrawn > 0) {
          gathered.push({ item: need.item, withdrawn: pulled.withdrawn });
        }
      } catch (pullError) {
        log('withdraw failed for ' + need.item + ': ' + pullError.message);
      }
    }
    inventory = drone.state().inventory || [];
    for (var a = 0; a < audit.length; a++) {
      audit[a].have = countInInventory(inventory, audit[a].item);
      audit[a].deficit = Math.max(0, audit[a].required - audit[a].have);
    }
  }
  missing = [];
  for (var m2 = 0; m2 < audit.length; m2++) {
    if (audit[m2].deficit > 0) missing.push(audit[m2]);
  }
}

if (cfg.dry_run === true) {
  return { ok: true, dryRun: true, item: filter, requested: wanted,
    outputPerCraft: outputPerCraft, plannedCrafts: plannedCrafts,
    ingredients: audit, missing: missing,
    hint: missing.length
      ? 'missing: ' + missing.map(function (x) { return x.item + ' x' + x.deficit; }).join(', ')
      : 'enough ingredients for the batch' };
}

if (missing.length) {
  return { ok: false, error: 'missing ingredients for ' + plannedCrafts + ' craft(s) of ' + filter,
    item: filter, plannedCrafts: plannedCrafts, ingredients: audit, missing: missing,
    hint: 'gather or withdraw: '
      + missing.map(function (x) { return x.item + ' x' + x.deficit; }).join(', ') };
}

var beforeCount = countInInventory(drone.state().inventory, filter);
var crafted = 0;
var failures = 0;
// One craft call is capped at 256 result items by the action; loop for more.
var passes = 0;
var maxPasses = Math.ceil(wanted / 256) + 1;
while (crafted < wanted && passes < maxPasses) {
  passes++;
  var remaining = wanted - crafted;
  try {
    var result = drone.craft({ filter: filter, count: Math.min(256, remaining) });
    var produced = (result && Number(result.crafted)) || 0;
    crafted += produced;
    if (produced <= 0) break;
  } catch (craftError) {
    failures++;
    log('craft failed: ' + craftError.message);
    break;
  }
}
var afterCount = countInInventory(drone.state().inventory, filter);
crafted = Math.max(crafted, afterCount - beforeCount);

var state = drone.state();
return {
  ok: crafted > 0,
  item: filter,
  requested: wanted,
  crafted: crafted,
  outputPerCraft: outputPerCraft,
  plannedCrafts: plannedCrafts,
  failures: failures,
  ingredients: audit,
  inventoryLeft: countInInventory(state.inventory, filter),
  hint: crafted >= wanted ? ''
    : 'crafted ' + crafted + ' of ' + wanted + '; ingredients ran out or the inventory is full'
};
