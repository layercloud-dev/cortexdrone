// Build-site survey. params: { x1,z1,x2,z2 } or { x,z,width,depth }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var request = {};
if (cfg.x1 !== undefined && cfg.z1 !== undefined && cfg.x2 !== undefined && cfg.z2 !== undefined) {
  request.x1 = Math.round(cfg.x1); request.z1 = Math.round(cfg.z1);
  request.x2 = Math.round(cfg.x2); request.z2 = Math.round(cfg.z2);
} else if (cfg.x !== undefined && cfg.z !== undefined) {
  request.x = Math.round(cfg.x); request.z = Math.round(cfg.z);
  request.width = clamp(cfg.width || 7, 3, 33);
  request.depth = clamp(cfg.depth || cfg.width || 7, 3, 33);
} else {
  request.width = clamp(cfg.width || 7, 3, 33);
  request.depth = clamp(cfg.depth || cfg.width || 7, 3, 33);
}
if (cfg.y !== undefined) request.y = Math.round(cfg.y);
request.height = clamp(cfg.height || 4, 1, 24);

var survey = drone.surveyBuildSite(request);
if (!survey || survey.ok === false) {
  return survey || { ok: false, error: 'site survey failed' };
}

var blockers = [];
if (survey.protectedColumns > 0) {
  blockers.push(survey.protectedColumns + ' columns contain chests/machines/OC blocks');
}
if (survey.liquidColumns > 0 && cfg.allow_liquid !== true) {
  blockers.push(survey.liquidColumns + ' columns contain liquid');
}
if (survey.unloadedColumns > 0) blockers.push(survey.unloadedColumns + ' columns are in unloaded chunks');

var clearCount = 0;
var clearMaterials = survey.clearMaterials || {};
for (var name in clearMaterials) clearCount += clearMaterials[name];

return {
  ok: true,
  suitable: blockers.length === 0 && survey.slope <= (cfg.max_slope || 3),
  blockers: blockers,
  floorY: survey.suggestedFloorY,
  groundYMin: survey.groundYMin,
  groundYMax: survey.groundYMax,
  slope: survey.slope,
  clearCount: clearCount,
  clearMaterials: clearMaterials,
  groundMaterials: survey.groundMaterials,
  warnings: survey.warnings,
  site: { x1: survey.x1, z1: survey.z1, x2: survey.x2, z2: survey.z2,
    width: survey.width, depth: survey.depth },
  recommendation: blockers.length
    ? 'pick another site or remove the blockers before building'
    : 'build the floor at y=' + survey.suggestedFloorY
      + ' and clear ' + clearCount + ' blocks first'
};
