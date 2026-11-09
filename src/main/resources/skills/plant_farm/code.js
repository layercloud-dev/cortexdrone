// Farm plot setup: till + sow. params:
// { crop, x1,z1,x2,z2 | x,z,width,length, y, max_columns, hoe, craft_hoe }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var crop = String(cfg.crop || 'wheat').toLowerCase();

/* Water within four blocks hydrates farmland (same level or one above). */
function waterWithinFour(d, bx, by, bz) {
  for (var dx = -4; dx <= 4; dx++) {
    for (var dz = -4; dz <= 4; dz++) {
      for (var dy = 0; dy <= 1; dy++) {
        try {
          var probe = d.getBlock({ x: bx + dx, y: by + dy, z: bz + dz });
          if (probe && probe.isLiquid === true) return true;
        } catch (ignored) {
        }
      }
    }
  }
  return false;
}

var profiles = {
  wheat: { seed: 'wheat_seeds', label: 'wheat seeds' },
  wheat_seeds: { seed: 'wheat_seeds', label: 'wheat seeds' },
  carrots: { seed: 'carrot', label: 'carrots' },
  carrot: { seed: 'carrot', label: 'carrots' },
  potatoes: { seed: 'potato', label: 'potatoes' },
  potato: { seed: 'potato', label: 'potatoes' },
  melon: { seed: 'melon_seeds', label: 'melon seeds' },
  pumpkin: { seed: 'pumpkin_seeds', label: 'pumpkin seeds' }
};
var profile = profiles[crop];
if (!profile) {
  return { ok: false, error: 'unsupported crop "' + crop + '"',
    hint: 'use wheat, carrots, potatoes, melon or pumpkin' };
}

// 1) Resolve the plot box in the XZ plane.
var x1, z1, x2, z2;
if (cfg.x1 !== undefined && cfg.z1 !== undefined && cfg.x2 !== undefined && cfg.z2 !== undefined) {
  x1 = Math.min(cfg.x1, cfg.x2); x2 = Math.max(cfg.x1, cfg.x2);
  z1 = Math.min(cfg.z1, cfg.z2); z2 = Math.max(cfg.z1, cfg.z2);
} else {
  var width = clamp(cfg.width || 5, LIMITS.dimensionMin, LIMITS.siteSideMax);
  var length = clamp(cfg.length || cfg.depth || width, LIMITS.dimensionMin, LIMITS.siteSideMax);
  var cx, cz;
  if (cfg.x !== undefined && cfg.z !== undefined) {
    cx = Math.round(cfg.x); cz = Math.round(cfg.z);
  } else {
    var player = drone.getPlayer({});
    if (!player || player.ok === false) {
      return { ok: false, error: 'player is offline; pass x/z or corners' };
    }
    var dx = Number(player.facingDx || 0);
    var dz = Number(player.facingDz || 0);
    if (dx === 0 && dz === 0) dz = 1;
    cx = player.blockX + dx * 3;
    cz = player.blockZ + dz * 3;
  }
  x1 = cx - Math.floor(width / 2);
  x2 = x1 + width - 1;
  z1 = cz - Math.floor(length / 2);
  z2 = z1 + length - 1;
}

var soilY = cfg.y !== undefined ? Math.round(cfg.y) : null;
if (soilY === null) {
  var midX = Math.floor((x1 + x2) / 2);
  var midZ = Math.floor((z1 + z2) / 2);
  var top = drone.getTopY({ x: midX, z: midZ });
  soilY = top && Number(top.topY) > 0 ? Number(top.topY) - 1 : null;
}
if (soilY === null) {
  return { ok: false, error: 'could not resolve the soil level; pass y' };
}

// 2) Hoe, crafting a wooden one when needed.
var hoeFilter = cfg.hoe || 'hoe';
var hoes = countInInventory(drone.state().inventory, hoeFilter);
if (hoes <= 0 && cfg.craft_hoe !== false) {
  try {
    skills.call('craft_items', { filter: 'wooden_hoe', count: 1 });
  } catch (hoeError) {
    log('hoe craft failed: ' + hoeError.message);
  }
  hoes = countInInventory(drone.state().inventory, hoeFilter);
}
if (hoes <= 0) {
  return { ok: false, error: 'no hoe in the drone inventory',
    hint: 'craft a wooden hoe (2 planks + 2 sticks) or take one from the player' };
}

var seeds = countInInventory(drone.state().inventory, profile.seed);
if (seeds <= 0) {
  return { ok: false, error: 'no ' + profile.label + ' in the drone inventory',
    hint: 'harvest grass for wheat seeds, or take seeds from the player/chests' };
}

// 3) Till and sow column by column.
var maxColumns = clamp(cfg.max_columns || 64, 1, SKILL_LIMITS.budget256);
var tilled = 0;
var planted = 0;
var skipped = 0;
var dry = 0;
var waterNearby = false;
var columns = (x2 - x1 + 1) * (z2 - z1 + 1);
var budget = maxColumns;
var columnsDone = 0;
var full = false;

for (var x = x1; x <= x2 && !full; x++) {
  for (var z = z1; z <= z2; z++) {
    if (columnsDone >= budget) { full = true; break; }

    var above = null;
    var soil = null;
    try {
      above = drone.getBlock({ x: x, y: soilY + 1, z: z });
      soil = drone.getBlock({ x: x, y: soilY, z: z });
    } catch (readError) {
      skipped++;
      continue;
    }
    if (!above || !above.isAir) { skipped++; continue; }
    if (!soil || soil.isAir || soil.isLiquid) { skipped++; continue; }

    var isFarmland = nameMatches(soil.name, soil.registryName, 'farmland', soil.oreDict);
    var isTillable = nameMatches(soil.name, soil.registryName, 'grass', soil.oreDict)
      || nameMatches(soil.name, soil.registryName, 'dirt', soil.oreDict);
    if (isFarmland && Number(soil.meta) <= 0) dry++;
    if (!isFarmland && !isTillable) { skipped++; continue; }

    if (!waterNearby) {
      waterNearby = waterWithinFour(drone, x, soilY, z);
    }

    if (!isFarmland) {
      try {
        drone.goto({ standoffOf: { x: x, y: soilY, z: z }, allow_dig: false });
        var tilledResult = drone.useItem({ item: hoeFilter, x: x, y: soilY, z: z, side: 1 });
        if (tilledResult && tilledResult.used) {
          tilled++;
          var nowSoil = drone.getBlock({ x: x, y: soilY, z: z });
          if (!nowSoil || !nameMatches(nowSoil.name, nowSoil.registryName, 'farmland', nowSoil.oreDict)) {
            skipped++;
            continue;
          }
          if (Number(nowSoil.meta) <= 0) dry++;
        } else {
          skipped++;
          continue;
        }
      } catch (tillError) {
        skipped++;
        log('till skipped at ' + x + ',' + soilY + ',' + z + ': ' + tillError.message);
        continue;
      }
    }

    if (countInInventory(drone.state().inventory, profile.seed) <= 0) {
      return { ok: planted > 0, crop: crop, tilled: tilled, planted: planted,
        skipped: skipped, dry: dry, waterNearby: waterNearby,
        seedsLeft: 0, area: { x1: x1, z1: z1, x2: x2, z2: z2, y: soilY },
        hint: 'ran out of ' + profile.label + ' after ' + planted + ' plants' };
    }
    try {
      drone.goto({ standoffOf: { x: x, y: soilY, z: z }, allow_dig: false });
      var sow = drone.useItem({ item: profile.seed, x: x, y: soilY, z: z, side: 1 });
      if (sow && sow.used) planted++;
      else skipped++;
    } catch (sowError) {
      skipped++;
      log('sow skipped at ' + x + ',' + soilY + ',' + z + ': ' + sowError.message);
    }
    columnsDone++;
  }
}

var finalSeeds = countInInventory(drone.state().inventory, profile.seed);
return {
  ok: planted > 0,
  crop: crop,
  tilled: tilled,
  planted: planted,
  skipped: skipped,
  columns: columns,
  dry: dry,
  waterNearby: waterNearby,
  seedsLeft: finalSeeds,
  area: { x1: x1, z1: z1, x2: x2, z2: z2, y: soilY },
  hint: planted > 0
    ? (waterNearby ? '' : 'no water within 4 blocks: growth will be slow, bring a water bucket')
    : 'nothing was planted; check the plot is open soil at y=' + soilY
};
