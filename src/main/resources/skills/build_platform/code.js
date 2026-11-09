// Platform / bridge. params: { width, depth, block, style, y, x/z }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var width = clamp(cfg.width || 7, LIMITS.footprintMin, LIMITS.footprintMax);
var depth = clamp(cfg.depth || width, LIMITS.footprintMin, LIMITS.footprintMax);
var material = cfg.block || 'cobblestone';
var style = cfg.bridge === true ? 'bridge' : 'platform';

var center = null;
if (cfg.x !== undefined && cfg.z !== undefined) {
  center = { x: Math.round(cfg.x), z: Math.round(cfg.z) };
} else {
  var player = drone.getPlayer({});
  if (!player || player.ok === false) return { ok: false, error: 'player is offline' };
  var dx = Number(player.facingDx || 0);
  var dz = Number(player.facingDz || 0);
  if (dx === 0 && dz === 0) dz = 1;
  center = { x: player.blockX + dx * 4, z: player.blockZ + dz * 4 };
}

var halfW = Math.floor(width / 2);
var halfD = Math.floor(depth / 2);
var site = { x1: center.x - halfW, z1: center.z - halfD,
  x2: center.x - halfW + width - 1, z2: center.z - halfD + depth - 1 };

var survey = skills.call('survey_build_site', {
  x1: site.x1, z1: site.z1, x2: site.x2, z2: site.z2,
  height: style === 'bridge' ? 2 : 3,
  allow_liquid: cfg.allow_liquid === true
});
if (!survey || survey.ok === false) return survey;
if (survey.blockers && survey.blockers.length) {
  return { ok: false, error: 'platform site is blocked: ' + survey.blockers.join('; '),
    site: survey.site };
}

var plan = drone.planStructure({
  style: style, width: width, depth: depth,
  floor: material, walls: material, corners: material,
  foundation: cfg.foundation || material,
  x1: site.x1, z1: site.z1, x2: site.x2, z2: site.z2,
  y: cfg.y !== undefined ? cfg.y : survey.floorY
});
if (!plan || plan.ok === false) return plan || { ok: false, error: 'platform plan failed' };

var build = skills.call('place_build_plan', {
  plan: plan, allow_substitute: cfg.allow_substitute !== false,
  verify: true, max_place: plan.buildCount
});
return { ok: !!(build && build.ok), site: survey.site, floorY: plan.floorY,
  kind: style, placed: build && build.placed, failed: build && build.failed,
  missingMaterials: build && build.missingMaterials, detail: build };
