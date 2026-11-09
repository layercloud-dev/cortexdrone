// Composition: mine_blocks -> deliver_items.
var cfg = (typeof params === 'object' && params !== null) ? params : {};
var mined = skills.call('mine_blocks', {
  filter: cfg.filter || 'iron_ore',
  radius: cfg.radius || LIMITS.scanRadiusDefault,
  count: cfg.count || 8
});
if (!mined || mined.ok === false || !mined.mined) {
  return mined || { ok: false, error: 'mining returned nothing' };
}
var delivered = skills.call('deliver_items', {
  filter: cfg.deliver_filter || 'all',
  count: MAX_TRANSFER_COUNT,
  player: cfg.player
});
return {
  ok: !!(delivered && delivered.ok),
  mined: mined.mined,
  blocks: mined.blocks,
  delivered: delivered && delivered.given,
  delivery: delivered
};
