// Farm pass. params: { crop, radius, limit, deliver, store }
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var crop = String(cfg.crop || 'wheat').toLowerCase();
var harvested = null;

if (crop === 'sugarcane' || crop === 'sugar_cane' || crop === 'cane') {
  harvested = skills.call('sugarcane_farm', {
    radius: cfg.radius || LIMITS.scanRadiusDefault, limit: cfg.limit || SKILL_LIMITS.largeLimitDefault
  });
} else if (crop === 'bamboo') {
  harvested = skills.call('bamboo_farm', {
    radius: cfg.radius || LIMITS.scanRadiusDefault, limit: cfg.limit || 64
  });
} else if (crop === 'melon' || crop === 'pumpkin') {
  harvested = skills.call('melon_pumpkin_farm', {
    kind: crop, radius: cfg.radius || LIMITS.scanRadiusDefault, limit: cfg.limit || SKILL_LIMITS.smallLimitDefault
  });
} else {
  harvested = skills.call('harvest_crops', {
    crop: crop, radius: cfg.radius || LIMITS.scanRadiusDefault, limit: cfg.limit || 16
  });
}

if (!harvested || harvested.ok === false) {
  return harvested || { ok: false, error: 'harvest returned nothing' };
}

var collected = skills.call('collect_drops', {
  radius: Math.min(LIMITS.collectRadiusMax, cfg.radius || LIMITS.scanRadiusDefault), filter: 'all', max_clusters: SKILL_LIMITS.maxClustersLarge
});

var stored = null;
var delivered = null;
if (cfg.deliver === true) {
  delivered = skills.call('deliver_items', { filter: 'all', count: MAX_TRANSFER_COUNT });
} else if (cfg.store === true) {
  stored = skills.call('store_items', { radius: cfg.chest_radius || LIMITS.chestRadiusDefault });
}

return {
  ok: harvested.harvested > 0,
  crop: crop,
  harvested: harvested.harvested,
  replanted: harvested.replanted,
  collected: collected && collected.collected,
  delivered: delivered && delivered.given,
  stored: stored && stored.deposited,
  detail: harvested
};
