const drone = {
  getStatus: async () => { await _flushBatches(); const st = await rpc('get_drone', { loopId: typeof LOOP_ID === 'number' ? LOOP_ID : 0 }); return Object.assign(Object.create(drone), st); },
  waitUntilIdle: async (maxWaitMs = 150000) => {
    await _flushBatches();
    return await _waitIdle(maxWaitMs);
  },
  flyTo: async (x, y, z, maxWaitMs = 45000) => {
    await _flushBatches();
    const target = Array.isArray(x) ? { x: x[0], y: x[1], z: x[2] } : (_isObj(x) ? x : { x, y, z });
    const r = await rpc('move_to', target);
    const st = await _waitTask(
      (s) => s.mode !== 'GOTO' || Math.hypot((s.x || 0) - target.x, (s.y || 0) - target.y, (s.z || 0) - target.z) <= 2.2,
      maxWaitMs
    );
    return { ...r, ...st };
  },
  breakBlock: async (x, y, z) => {
    await _flushBatches();
    const target = Array.isArray(x) ? { x: x[0], y: x[1], z: x[2] } : (_isObj(x) ? x : { x, y, z });
    const r = await rpc('break_block', target);
    if (r && r.queued > 0) {
      _gatheredItems = true;
      const st = await _waitIdle();
      return { ...r, broken: st.minedCount || 1, ...st };
    }
    return r;
  },
  mine: async (x, y, z) => {
    if (typeof x === 'string' || (_isObj(x) && (x.target || x.filter) && x.x === undefined)) {
      await _flushBatches();
      const args = _isObj(x)
        ? { target: x.target || x.filter, count: x.count || x.maxCount || 0, untilFull: !!x.untilFull }
        : { target: x, ...(typeof y === 'number' ? { count: y } : {}), ...(typeof z === 'boolean' ? { untilFull: z } : {}) };
      const r = await rpc('mine_filter', args);
      if (r && r.queued > 0) {
        _gatheredItems = true;
        const st = await _waitIdle();
        return { ...r, ...st };
      }
      return r;
    }
    if (Array.isArray(x)) { _mineBatch.push([x[0], x[1], x[2]]); }
    else if (_isObj(x)) { _mineBatch.push([x.x, x.y, x.z]); }
    else { _mineBatch.push([x, y, z]); }
    return { ok: true, buffered: _mineBatch.length };
  },
  mineMany: async (blocks, exactOnly = true) => {
    await _flushBatches();
    const r = await rpc('queue_mine', _isObj(blocks) ? { deliver: false, ...blocks } : { blocks, exactOnly, deliver: false });
    if (r && r.queued > 0) {
      _gatheredItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  mineRect: async (x1, y1, z1, x2, y2, z2, filter = 'all') => {
    await _flushBatches();
    const r = await rpc('mine_rect', _isObj(x1) ? x1 : { x1, y1, z1, x2, y2, z2, filter });
    if (r && r.queued > 0) {
      _gatheredItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  mineBox: async (anchor = 'under_player', width = 3, height = 3, length = 3) => {
    await _flushBatches();
    const r = await rpc('mine_box', _isObj(anchor) ? anchor : { anchor, width, height, length });
    if (r && r.queued > 0) {
      _gatheredItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  gatherDistant: async (target = 'jungle', maxRadius = 1200, count = 0, untilFull = false) => {
    await _flushBatches();
    const r = await rpc('gather_distant', _isObj(target) ? target : { target, maxRadius, count, untilFull });
    if (r && r.queued > 0) {
      _gatheredItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  placeBlock: async (x, y, z, block = 'any', meta = -1) => {
    if (_isObj(x)) { _buildBatch.push({ x: x.x, y: x.y, z: x.z, block: x.block || x.with || 'any', meta: x.meta ?? -1, replace: !!x.replace }); }
    else if (Array.isArray(x)) { _buildBatch.push({ x: x[0], y: x[1], z: x[2], block: y || 'any', meta: z ?? -1, replace: false }); }
    else { _buildBatch.push({ x, y, z, block, meta, replace: false }); }
    return { ok: true, buffered: _buildBatch.length };
  },
  placeMany: async (tasks, block = 'any', meta = -1) => {
    await _flushBatches();
    const r = await rpc('queue_build', _isObj(tasks) ? tasks : { tasks, block, meta });
    if (r && r.queued > 0) {
      _handledItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  placeBox: async (x1, y1, z1, x2, y2, z2, block = 'any', hollow = false, meta = -1) => {
    await _flushBatches();
    const r = await rpc('build_box', _isObj(x1) ? x1 : { x1, y1, z1, x2, y2, z2, block, hollow, meta });
    if (r && r.queued > 0) {
      _handledItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  buildStructure: async (spec = {}) => {
    await _flushBatches();
    const opts = _isObj(spec) ? spec : { block: String(spec) };
    const r = await rpc('build_structure', opts);
    if (r && r.buildCount > 0) {
      _handledItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  replaceBlock: async (x, y, z, block = 'any', meta = -1) => {
    if (_isObj(x)) { _buildBatch.push({ x: x.x, y: x.y, z: x.z, block: x.block || x.with || 'any', meta: x.meta ?? -1, replace: true, ...(x.onlyReplace ? { onlyReplace: x.onlyReplace } : {}) }); }
    else if (Array.isArray(x)) { _buildBatch.push({ x: x[0], y: x[1], z: x[2], block: y || 'any', meta: z ?? -1, replace: true }); }
    else { _buildBatch.push({ x, y, z, block, meta, replace: true }); }
    return { ok: true, buffered: _buildBatch.length };
  },
  replaceBlocks: async (blocks, block = 'any', meta = -1) => {
    await _flushBatches();
    const r = await rpc('replace_blocks', _isObj(blocks) ? blocks : { blocks, block, meta });
    if (r && r.queued > 0) {
      _handledItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  replaceBox: async (x1, y1, z1, x2, y2, z2, block = 'any', onlyReplace = null, meta = -1) => {
    await _flushBatches();
    const r = await rpc('replace_box', _isObj(x1) ? x1 : { x1, y1, z1, x2, y2, z2, block, ...(onlyReplace ? { onlyReplace } : {}), meta });
    if (r && r.queued > 0) {
      _handledItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  replaceRoom: async (surface = 'floor', block = 'any', onlyReplace = null, meta = -1) => {
    await _flushBatches();
    const r = await rpc('replace_room', _isObj(surface) ? surface : { surface, block, ...(onlyReplace ? { onlyReplace } : {}), meta });
    if (r && r.queued > 0) {
      _handledItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  plugLiquids: async (opts = 12) => {
    await _flushBatches();
    const r = await rpc('plug_liquids', typeof opts === 'number' ? { radius: opts } : (opts || {}));
    if (r && r.queued > 0) {
      _handledItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  scoopLiquid: async (type = 'lava', count = 1) => {
    await _flushBatches();
    const r = await rpc('scoop_liquid', _isObj(type) ? type : { type, count });
    if (r && (r.queued > 0 || r.scooped > 0)) {
      _gatheredItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  placeLiquid: async (x, y, z, type = 'water') => {
    await _flushBatches();
    _handledItems = true;
    return await rpc('place_liquid', _isObj(x) ? x : (Array.isArray(x) ? { x: x[0], y: x[1], z: x[2], type: y || 'water' } : { x, y, z, type }));
  },
  clearQueues: async () => { _mineBatch.length = 0; _buildBatch.length = 0; return await rpc('clear_queues', {}); },
  farm: async (target = 'crop', radius = 48) => {
    await _flushBatches();
    const r = await rpc('farm', _isObj(target) ? target : { target, radius });
    if (r && r.queued > 0) {
      _gatheredItems = true;
      const st = await _waitIdle();
      return { ...r, harvested: r.harvested ?? r.queued, ...st };
    }
    return r;
  },
  chop: async (x, y, z) => {
    await _flushBatches();
    const target = _isObj(x) ? x : (Array.isArray(x) ? { x: x[0], y: x[1], z: x[2] } : { x, y, z });
    const r = await rpc('chop', target);
    if (r && r.queued > 0) {
      _gatheredItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  fertilize: async (radius = 12) => {
    await _flushBatches();
    const target = _isObj(radius) ? radius : (typeof radius === 'number' ? { radius } : { radius: 12 });
    return await rpc('fertilize', target);
  },
  interactBlock: async (x, y, z, side) => {
    await _flushBatches();
    const target = _isObj(x)
      ? x
      : (Array.isArray(x)
        ? { x: x[0], y: x[1], z: x[2], ...(typeof side === 'number' ? { side } : {}) }
        : { x, y, z, ...(typeof side === 'number' ? { side } : {}) });
    const r = await rpc('interact_block', target);
    if (r && r.activated !== undefined) return r;
    try {
      const st = await _waitTask((s) => Math.hypot((s.x || 0) - (target.x || 0), (s.y || 0) - (target.y || 0), (s.z || 0) - (target.z || 0)) <= 4.5, 30000);
      if (st && !st.aborted) {
        const r2 = await rpc('interact_block', target);
        return { ...r2, moved: true };
      }
    } catch (e) { /* fall through with original result */ }
    return r;
  },
  useItem: async (a = 0, b, c, d2, e) => {
    await _flushBatches();
    // Accepted forms:
    //   useItem({ item:'hoe', x, y, z, side })   useItem('hoe', x, y, z)
    //   useItem(0, 'hoe', x, y, z)               useItem(0)  (current hotbar slot)
    let target;
    if (_isObj(a)) {
      target = { ...a };
    } else if (typeof a === 'number') {
      target = { slot: a };
      let rest = [b, c, d2, e];
      if (typeof b === 'string') { target.item = b; rest = [c, d2, e]; }
      if (typeof rest[0] === 'number') {
        target.x = rest[0]; target.y = rest[1]; target.z = rest[2];
        if (typeof rest[3] === 'number') target.side = rest[3];
      }
    } else {
      target = { item: String(a) };
      if (typeof b === 'number') {
        target.x = b; target.y = c; target.z = d2;
        if (typeof e === 'number') target.side = e;
      }
    }
    const r = await rpc('use_item', target);
    if (r && r.used !== undefined) return r;
    try {
      const st = await _waitIdle(20000);
      return { ...r, ...st };
    } catch (e2) {
      return r;
    }
  },
  attackEntity: async (target = 'hostile') => {
    await _flushBatches();
    const args = typeof target === 'number' ? { id: target } : (_isObj(target) ? target : { filter: String(target) });
    const r = await rpc('attack_entity', args);
    if (r && r.started) {
      _gatheredItems = true;
      const st = await _waitIdle();
      return { ...r, killed: (st.huntedCount || 0) > 0, ...st };
    }
    return r;
  },
  hunt: async (filter = 'sheep|cow|pig', count = 10) => {
    await _flushBatches();
    const r = await rpc('hunt', _isObj(filter) ? filter : { filter, count });
    if (r && r.nearbyMatching > 0) {
      _gatheredItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  cook: async (filter = 'meat', count = 64) => {
    await _flushBatches();
    const r = await rpc('cook', _isObj(filter) ? filter : { filter, count });
    if (r && r.queued > 0) {
      _gatheredItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  smelt: async (filter = 'ores', count = 64) => {
    await _flushBatches();
    const r = await rpc('smelt', _isObj(filter) ? filter : { filter, count });
    if (r && r.queued > 0) {
      _gatheredItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  craft: async (filter = 'planks', count = 1) => {
    await _flushBatches();
    const r = await rpc('craft', _isObj(filter) ? filter : { filter, count });
    if (r && r.crafted > 0) {
      _gatheredItems = true;
    }
    return r;
  },
  machineCraft: async (filter = 'plate_iron', machine = '', count = 1, radius = 32) => {
    await _flushBatches();
    const r = await rpc('machine_craft', _isObj(filter) ? filter : { filter, ...(machine ? { machine } : {}), count, radius });
    if (r && r.loaded) {
      _gatheredItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  driveAnvil: async (filter = 'plate_iron', radius = 32) => {
    await _flushBatches();
    const r = await rpc('anvil_drive', _isObj(filter) ? filter : { filter, radius });
    if (r && (r.guiOpened || r.constructionProduced > 0)) {
      _gatheredItems = true;
    }
    return r;
  },
  suckItems: async (radius = 8) => {
    await _flushBatches();
    return await rpc('suck_items', _isObj(radius) ? radius : { radius });
  },
  sortChests: async (radius = 24, mode = 'all') => { await _flushBatches(); return await rpc('sort_chests', _isObj(radius) ? radius : { radius, mode }); },
  transferContainers: async (from = 'crate', to = 'chest', radius = 32, sortAfter = true) => { await _flushBatches(); _handledItems = true; return await rpc('transfer_containers', _isObj(from) ? from : { from, to, radius, sortAfter }); },
  carryPlayer: async (x, y, z) => {
    await _flushBatches();
    const r = await rpc('carry_player', Array.isArray(x) ? { x: x[0], y: x[1], z: x[2] } : (_isObj(x) ? x : (typeof x === 'number' ? { x, y, z } : {})));
    if (r && r.queued > 0) {
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  getBreadcrumbs: (limit = 64) => _isObj(limit) ? rpc('get_breadcrumbs', limit) : rpc('get_breadcrumbs', { limit }),
  retraceBreadcrumbs: async (steps = 48, toSurface = true) => {
    await _flushBatches();
    const r = await rpc('retrace_breadcrumbs', _isObj(steps) ? steps : { steps, toSurface });
    if (r && r.waypointsQueued > 0) {
      const st = await _waitIdle(35000);
      return { ...r, ...st };
    }
    return r;
  },
  escapeCave: async () => {
    await _flushBatches();
    const r = await rpc('escape_cave', {});
    if (r && r.steps > 0) {
      const st = await _waitIdle(35000);
      return { ...r, ...st };
    }
    return r;
  },
  takeFromPlayer: async (filter = 'any', count = 512, playerName) => {
    await _flushBatches();
    return await rpc('take_from_player', _isObj(filter) ? filter : { filter, count, ...(playerName ? { player: playerName } : {}) });
  },
  giveToPlayer: async (filter = 'all', count = 512, playerName) => {
    await _flushBatches();
    _handledItems = true;
    const r = await rpc('give_to_player', _isObj(filter) ? filter : { filter, count, ...(playerName ? { player: playerName } : {}) });
    if (r && r.flyingToPlayer) {
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  withdrawChest: async (a = 'any', b = 64, c, d, e) => {
    await _flushBatches();
    _gatheredItems = true;
    if (_isObj(a)) return await rpc('withdraw_chest', a);
    if (typeof a === 'number') return await rpc('withdraw_chest', { x: a, y: b, z: c, filter: d || 'any', count: e ?? 64 });
    return await rpc('withdraw_chest', { filter: a, count: b, ...(typeof c === 'number' ? { x: c, y: d, z: e } : {}) });
  },
  depositChest: async (x, y, z) => {
    await _flushBatches();
    _handledItems = true;
    const r = await rpc('deposit_chest', _isObj(x) ? x : (typeof x === 'number' ? { x, y, z } : {}));
    if (r && r.flyingToChest) {
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  moveSlot: async (opts) => { await _flushBatches(); return await rpc('move_slot', opts || {}); },
  // Torch-free illumination aura. While active the drone lights the area itself;
  // no torch item is used and no block is placed. Toggle any time, and optionally
  // set a colour (hex/rgb/name), radius and light level:
  //   await drone.light(true)                     // normal white light
  //   await drone.light(false)                    // restore the world
  //   await drone.light({ color: 'red' })         // enable + coloured glow
  //   await drone.light({ enabled: true, radius: 8, level: 15, color: '#FF9500' })
  light: async (enabled, opts = {}) => {
    await _flushBatches();
    let a;
    if (_isObj(enabled)) a = { ...enabled };
    else if (typeof enabled === 'boolean') a = { enabled, ...( _isObj(opts) ? opts : {} ) };
    else if (typeof enabled === 'string') a = { enabled: true, color: enabled, ...( _isObj(opts) ? opts : {} ) };
    else a = { toggle: true };
    return await rpc('light', a);
  },
  lightOff: async () => { await _flushBatches(); return await rpc('light', { enabled: false }); },
  // Legacy alias kept so old skills keep working; it now drives the aura, not torches.
  torch: async (enabled) => {
    await _flushBatches();
    if (typeof enabled === 'boolean') return await rpc('light', { enabled });
    if (_isObj(enabled)) return await rpc('light', enabled);
    return await rpc('light', { toggle: true });
  },
  follow: async (target) => { await _flushBatches(); return await rpc('follow', _isObj(target) ? target : (target ? { target } : {})); },
  stay: async () => { await _flushBatches(); _handledItems = true; return await rpc('stay', {}); },
  stop: async () => { await _flushBatches(); _handledItems = true; return await rpc('stop', {}); },
  come: async () => {
    await _flushBatches();
    const r = await rpc('come', {});
    const st = await _waitIdle(20000);
    return { ...r, ...st };
  },
  guard: async (target, filter) => { await _flushBatches(); return await rpc('guard', _isObj(target) ? target : { ...(target ? { target } : {}), ...(filter ? { filter } : {}) }); },
  patrol: async (radius = 36, filter = 'hostile|mutant') => {
    await _flushBatches();
    const r = await rpc('patrol', _isObj(radius) ? radius : (typeof radius === 'string' ? { filter: radius, radius: typeof filter === 'number' ? filter : 36 } : { radius, filter }));
    if (r && !r.ok) return r;
    try {
      const st = await _waitTask((s) => s.mode === 'PATROL', 30000);
      return { ...r, ...st };
    } catch (e) {
      return r;
    }
  },
  drop: async (filter = 'all') => {
    await _flushBatches();
    _handledItems = true;
    const r = await rpc('drop', _isObj(filter) ? filter : { filter });
    const st = await _waitIdle();
    return { ...r, ...st };
  },
  say: (message) => rpc('say', _isObj(message) ? message : { message: String(message) }),
  setStatusText: (text) => rpc('set_status_text', _isObj(text) ? text : { text: String(text) })
};
