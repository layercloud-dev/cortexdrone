// Wall. params: { length, height, block, x1,z1,x2,z2 }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var length = clamp(cfg.length || 6, 3, 19);
var height = clamp(cfg.height || 3, 2, 8);
var material = cfg.block || 'cobblestone';
var site = null;

if (cfg.x1 !== undefined && cfg.z1 !== undefined && cfg.x2 !== undefined && cfg.z2 !== undefined) {
  site = { x1: Math.min(cfg.x1, cfg.x2), z1: Math.min(cfg.z1, cfg.z2),
    x2: Math.max(cfg.x1, cfg.x2), z2: Math.max(cfg.z1, cfg.z2) };
} else {
  var player = drone.getPlayer({});
  if (!player || player.ok === false) {
    return { ok: false, error: 'player is offline; pass explicit x1/z1/x2/z2' };
  }
  var dx = Number(player.facingDx || 0);
  var dz = Number(player.facingDz || 0);
  if (dx === 0 && dz === 0) dz = 1;
  var startX = player.blockX + dx * 3;
  var startZ = player.blockZ + dz * 3;
  var perpX = -dz;
  var perpZ = dx;
  var endX = startX + perpX * (length - 1);
  var endZ = startZ + perpZ * (length - 1);
  site = { x1: Math.min(startX, endX), z1: Math.min(startZ, endZ),
    x2: Math.max(startX, endX), z2: Math.max(startZ, endZ) };
}

var survey = skills.call('survey_build_site', {
  x1: site.x1, z1: site.z1, x2: site.x2, z2: site.z2, height: height + 2
});
if (!survey || survey.ok === false) return survey;
if (survey.blockers && survey.blockers.length) {
  return { ok: false, error: 'wall site is blocked: ' + survey.blockers.join('; '),
    site: survey.site };
}

var plan = drone.planStructure({
  style: 'wall', height: height, width: site.x2 - site.x1 + 1,
  depth: site.z2 - site.z1 + 1, walls: material, corners: material,
  floor: material, foundation: cfg.foundation || 'cobblestone',
  x1: site.x1, z1: site.z1, x2: site.x2, z2: site.z2,
  y: cfg.y !== undefined ? cfg.y : survey.floorY,
  door_side: cfg.door_side || 'south'
});
if (!plan || plan.ok === false) return plan || { ok: false, error: 'wall plan failed' };

var build = skills.call('place_build_plan', {
  plan: plan, allow_substitute: cfg.allow_substitute !== false,
  verify: true, max_place: plan.buildCount
});
return { ok: !!(build && build.ok), site: survey.site, floorY: plan.floorY,
  placed: build && build.placed, failed: build && build.failed,
  missingMaterials: build && build.missingMaterials, detail: build };
