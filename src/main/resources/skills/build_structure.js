// Correct structure build. params: { style, width, depth, height, x/z or x1/z1/x2/z2,
// y, roof_style, windows, floor, walls, corners, roof, foundation, window_block,
// door_side, allow_liquid, allow_substitute, dry_run, max_place }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var style = String(cfg.style || 'house').toLowerCase();
var width = clamp(cfg.width || 7, 3, 19);
var depth = clamp(cfg.depth || cfg.width || 7, 3, 19);
var height = clamp(cfg.height || 3, 2, 12);
var walls = cfg.walls || cfg.block || 'cobblestone';
var roofStyle = cfg.roof_style || cfg.roofStyle || 'flat';

// 1) Site: explicit box, explicit center, or a surveyed pad near the player.
var site = null;
var siteSource = '';
if (cfg.x1 !== undefined && cfg.z1 !== undefined && cfg.x2 !== undefined && cfg.z2 !== undefined) {
  site = { x1: clamp(cfg.x1, -30000000, 30000000), z1: clamp(cfg.z1, -30000000, 30000000),
    x2: clamp(cfg.x2, -30000000, 30000000), z2: clamp(cfg.z2, -30000000, 30000000),
    doorSide: cfg.door_side };
  site.x1 = Math.min(cfg.x1, cfg.x2); site.x2 = Math.max(cfg.x1, cfg.x2);
  site.z1 = Math.min(cfg.z1, cfg.z2); site.z2 = Math.max(cfg.z1, cfg.z2);
  siteSource = 'explicit_box';
} else if (cfg.x !== undefined && cfg.z !== undefined) {
  var centerX = Math.round(cfg.x);
  var centerZ = Math.round(cfg.z);
  var halfW = Math.floor(width / 2);
  var halfD = Math.floor(depth / 2);
  site = { x1: centerX - halfW, z1: centerZ - halfD,
    x2: centerX - halfW + width - 1, z2: centerZ - halfD + depth - 1,
    doorSide: cfg.door_side };
  siteSource = 'explicit_center';
} else {
  var found = drone.findBuildSite({ width: width, depth: depth,
    max_radius: clamp(cfg.radius || 24, 4, 64) });
  if (!found || found.ok === false) {
    return { ok: false, error: 'could not find a build pad near the player',
      hint: 'move to flatter ground or pass x/z explicitly' };
  }
  site = { x1: found.x1, z1: found.z1, x2: found.x2, z2: found.z2,
    doorSide: found.doorSide };
  siteSource = 'found_pad';
}

// 2) Survey before planning; a structure is never placed on unknown ground.
var survey = skills.call('survey_build_site', {
  x1: site.x1, z1: site.z1, x2: site.x2, z2: site.z2,
  height: height + 3, allow_liquid: cfg.allow_liquid === true
});
if (!survey || survey.ok === false) {
  return survey || { ok: false, error: 'site survey failed' };
}
if (survey.blockers && survey.blockers.length) {
  return { ok: false, error: 'site is not buildable: ' + survey.blockers.join('; '),
    site: survey.site, blockers: survey.blockers,
    hint: 'choose another place, drain the liquid, or relocate the protected blocks by hand' };
}
if (survey.slope > (cfg.max_slope || 4)) {
  return { ok: false, error: 'ground is too steep (slope ' + survey.slope + ' blocks)',
    site: survey.site, slope: survey.slope,
    hint: 'pick flatter ground, or use style=platform with a taller foundation' };
}

// 3) Floor sits on the highest ground so the building never floats over a dip.
var floorY = cfg.y !== undefined ? Math.round(cfg.y) : survey.floorY;
var droneState = drone.state();
if (droneState.x >= site.x1 - 1 && droneState.x <= site.x2 + 1
    && droneState.z >= site.z1 - 1 && droneState.z <= site.z2 + 1) {
  try {
    drone.goto({ x: site.x2 + 3, y: floorY + 3, z: site.z2 + 3, allow_dig: false });
  } catch (moveError) {
    log('could not clear the footprint: ' + moveError.message);
  }
}

// 4) Generate the phased blueprint.
var plan = drone.planStructure({
  style: style, width: width, depth: depth, height: height,
  roof_style: roofStyle, windows: cfg.windows !== false,
  floor: cfg.floor || walls, walls: walls,
  corners: cfg.corners || walls, roof: cfg.roof || walls,
  foundation: cfg.foundation || 'cobblestone',
  window_block: cfg.window_block || 'glass',
  x1: site.x1, z1: site.z1, x2: site.x2, z2: site.z2,
  y: floorY, door_side: cfg.door_side || site.doorSide
});
if (!plan || plan.ok === false) {
  return plan || { ok: false, error: 'blueprint generation failed' };
}

// 5) Material audit before the first block moves. A dry run stops here and
// never withdraws, mines or places anything.
var audit = skills.call('place_build_plan', { plan: plan, dry_run: true });
if (cfg.dry_run === true) {
  return { ok: true, dryRun: true, siteSource: siteSource, site: survey.site,
    floorY: floorY, style: style, roofStyle: roofStyle,
    survey: { slope: survey.slope, clearCount: survey.clearCount,
      warnings: survey.warnings },
    plan: { tasks: plan.buildCount, clear: plan.clearCount,
      materials: plan.materialsRequired, phases: plan.phases,
      door: plan.door },
    materials: audit.materials,
    missingMaterials: audit.missingMaterials,
    next: 're-run without dry_run to withdraw materials, clear and place it' };
}

// 6) Gather missing materials from the player and nearby chests.
var missing = (audit && audit.missingMaterials) ? audit.missingMaterials.slice(0) : [];
var gathered = {};
for (var m = 0; m < missing.length; m++) {
  var material = missing[m];
  try {
    var pulled = skills.call('withdraw_items', { filter: material,
      count: clamp(plan.materialsRequired[material] || 32, 1, 256) });
    if (pulled && pulled.withdrawn > 0) gathered[material] = pulled.withdrawn;
  } catch (withdrawError) {
    log('withdraw failed for ' + material + ': ' + withdrawError.message);
  }
}

// 7) Only mine stone-like material that is still missing after the withdrawal.
var auditAgain = skills.call('place_build_plan', { plan: plan, dry_run: true });
var stillMissing = (auditAgain && auditAgain.missingMaterials)
  ? auditAgain.missingMaterials.slice(0) : [];
var mineStone = false;
for (var mi = 0; mi < stillMissing.length; mi++) {
  var wanted = String(stillMissing[mi]).toLowerCase();
  if (wanted.indexOf('cobble') >= 0 || wanted === 'stone' || wanted === 'rock') {
    mineStone = true;
  }
}
if (mineStone && cfg.mine_missing !== false) {
  try {
    skills.call('mine_blocks', { filter: 'stone', radius: 16,
      count: clamp(plan.buildCount, 8, 128), veins: false });
  } catch (mineError) {
    log('stone gathering failed: ' + mineError.message);
  }
}

// 8) Execute: clear, then place in phase order, then verify.
var build = skills.call('place_build_plan', {
  plan: plan,
  allow_substitute: cfg.allow_substitute !== false,
  verify: true,
  max_place: clamp(cfg.max_place || plan.buildCount, 1, 800),
  max_failures: cfg.max_failures || 12
});

return {
  ok: !!(build && build.ok),
  style: style,
  roofStyle: roofStyle,
  siteSource: siteSource,
  site: survey.site,
  floorY: floorY,
  door: plan.door,
  surveyWarnings: survey.warnings,
  materialsRequired: plan.materialsRequired,
  gathered: gathered,
  plannedTasks: plan.buildCount,
  cleared: build && build.cleared,
  placed: build && build.placed,
  alreadyThere: build && build.alreadyThere,
  substituted: build && build.substituted,
  failed: build && build.failed,
  missingMaterials: build && build.missingMaterials,
  verification: build && build.verification,
  detail: build,
  hint: build && build.ok
    ? 'structure placed and verified; record a lesson if the site needed a workaround'
    : 'partially built; gather missing materials and re-run with the same plan'
};
