const drone = {
  getStatus: async () => { await _flushBatches(); const st = await rpc('get_drone', { loopId: typeof LOOP_ID === 'number' ? LOOP_ID : 0 }); return Object.assign(Object.create(drone), st); },
  waitUntilIdle: async (maxWaitMs = 150000) => {
    await _flushBatches();
    return await _waitIdle(maxWaitMs);
  },
  moveTo: async (x, y, z) => {
    await _flushBatches();
    const target = Array.isArray(x) ? { x: x[0], y: x[1], z: x[2] } : (_isObj(x) ? x : { x, y, z });
    const r = await rpc('move_to', target);
    const st = await _waitTask(
      (s) => s.mode !== 'GOTO' || Math.hypot((s.x || 0) - target.x, (s.y || 0) - target.y, (s.z || 0) - target.z) <= 2.2,
      45000
    );
    return { ...r, ...st };
  },
  goto: async (x, y, z) => await drone.moveTo(x, y, z),
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
  mineBlock: async (x, y, z) => {
    if (Array.isArray(x)) { _mineBatch.push([x[0], x[1], x[2]]); } else if (_isObj(x)) { _mineBatch.push([x.x, x.y, x.z]); } else { _mineBatch.push([x, y, z]); }
    return { ok: true, buffered: _mineBatch.length };
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
    return await drone.mineBlock(x, y, z);
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
  place: async (x, y, z, block = 'any', meta = -1) => await drone.placeBlock(x, y, z, block, meta),
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
  buildHouse: async (spec = {}) => await drone.buildStructure(_isObj(spec) ? { style: 'house', ...spec } : { style: 'house', wall: String(spec || 'cobblestone'), roof: 'planks', corners: 'log' }),
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
  fillBucket: async (type = 'lava', count = 1) => await drone.scoopLiquid(type, count),
  placeLiquid: async (x, y, z, type = 'water') => {
    await _flushBatches();
    _handledItems = true;
    return await rpc('place_liquid', _isObj(x) ? x : (Array.isArray(x) ? { x: x[0], y: x[1], z: x[2], type: y || 'water' } : { x, y, z, type }));
  },
  emptyBucket: async (x, y, z, type = 'water') => await drone.placeLiquid(x, y, z, type),
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
  /** Harvest (break) mature crops/trees and auto-replant via Forge IPlantable. */
  harvest: async (target = 'crop', radius = 48) => {
    await _flushBatches();
    const targetObj = _isObj(target) ? target : (Array.isArray(target) ? { target: String(target[0]) } : { target, radius });
    // harvest is an alias for farm; both share the same farming RPC path.
    const r = await rpc('harvest', targetObj);
    if (r && r.queued > 0) {
      _gatheredItems = true;
      const st = await _waitIdle();
      return { ...r, harvested: r.harvested ?? r.queued, ...st };
    }
    return r;
  },
  /** Chop (break) logs at (x,y,z) or chain-chop the tree column around it. */
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
  /**
   * Right-click / activate a block at (x,y,z): doors, levers, buttons, furnaces, machines.
   * Awaitable: flies to within interaction range (4m) first, then activates.
   * Resolves {activated, moved, ...} so `await drone.interact(...)` only returns
   * once the action has actually fired, not once the RPC was queued.
   */
  interactBlock: async (x, y, z) => {
    await _flushBatches();
    const target = _isObj(x) ? x : (Array.isArray(x) ? { x: x[0], y: x[1], z: x[2] } : { x, y, z });
    const r = await rpc('interact_block', target);
    if (r && r.activated !== undefined) return r;
    // RPC was queued rather than executed inline; move closer and retry once.
    try {
      const st = await _waitTask((s) => Math.hypot((s.x || 0) - (target.x || 0), (s.y || 0) - (target.y || 0), (s.z || 0) - (target.z || 0)) <= 4.5, 30000);
      if (st && !st.aborted) {
        const r2 = await rpc('interact_block', target);
        return { ...r2, moved: true };
      }
    } catch (e) { /* fall through with original result */ }
    return r;
  },
  /**
   * Bypass a GUI / right-click an item in hand (doors, food, bone meal, bucket, etc.).
   * Awaitable: waits for the use to settle (idle or timeout) before resolving,
   * so chains like `await drone.useItem(0); await drone.moveTo(...)` sequence.
   */
  useItem: async (slotOrFilter = 0, itemFilter) => {
    await _flushBatches();
    const target = _isObj(slotOrFilter)
      ? slotOrFilter
      : (typeof slotOrFilter === 'number'
        ? (itemFilter ? { slot: slotOrFilter, item: String(itemFilter) } : { slot: slotOrFilter })
        : { item: String(slotOrFilter) });
    const r = await rpc('use_item', target);
    if (r && r.used !== undefined) return r;
    try {
      const st = await _waitIdle(20000);
      return { ...r, ...st };
    } catch (e) {
      return r;
    }
  },
  /** Alias for interactBlock. Awaitable to the same completion contract. */
  interact: async (x, y, z) => await drone.interactBlock(x, y, z),
  /** Fight / hit an entity by id or filter (hostile/pig/creeper...). Flys to it and kills it. */
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
  /** Machine craft via a nearby HBM processing TileEntity (Assembly/Chemical/Blast/Anvil...). */
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
  anvilCraft: async (filter = 'plate_iron', count = 1, radius = 32) => {
    await _flushBatches();
    const r = await rpc('anvil_craft', _isObj(filter) ? filter : { filter, machine: 'anvil', count, radius });
    if (r && r.loaded) {
      _gatheredItems = true;
      const st = await _waitIdle();
      return { ...r, ...st };
    }
    return r;
  },
  getMachineRecipes: async (filter = '', machine = '') => {
    await _flushBatches();
    return await rpc('get_machine_recipes', _isObj(filter) ? filter : { ...(filter ? { filter } : {}), ...(machine ? { machine } : {}) });
  },
  getMachineRecipe: async (filter = 'plate_iron', machine = '', radius = 32) => {
    await _flushBatches();
    return await rpc('get_machine_recipe', _isObj(filter) ? filter : { filter, ...(machine ? { machine } : {}), radius });
  },
  scanMachines: async (machine = '', radius = 32) => {
    await _flushBatches();
    return await rpc('scan_machines', _isObj(machine) ? machine : { ...(machine ? { machine } : {}), radius });
  },
  // Aliases kept in lockstep with world.*: probe logs showed "drone.machineCraft is
  // not a function" and "world.scanMachines is not a function" depending on which
  // surface the LLM drifted to; both surfaces must expose both names.
  getMachines: async (machine = '', radius = 32) => await drone.scanMachines(machine, radius),
  machineIngredients: async (filter = 'plate_iron', machine = '', radius = 32) => {
    await _flushBatches();
    return await rpc('machine_ingredients', _isObj(filter) ? filter : { filter, ...(machine ? { machine } : {}), radius });
  },
  getMachineIngredients: async (filter = 'plate_iron', machine = '', radius = 32) => await drone.machineIngredients(filter, machine, radius),
  /** Drive the HBM NTM Anvil (GUI-only). Introspects via kb.lookup/machineConfigRecipes, stages ingredients, then tries ContainerAnvil on the fake player; if the GUI path is unavailable it returns blocker {tier, overlay, missingIngredients} and says it via droneSay. Construction recipes run the AnvilCraftPacket-equivalent server path (InventoryUtil on fake-player mainInventory); heat-aware smithing heats ItemHot inputs via ItemHot.heatUp. */
  driveAnvil: async (filter = 'plate_iron', radius = 32) => {
    await _flushBatches();
    const r = await rpc('anvil_drive', _isObj(filter) ? filter : { filter, radius });
    if (r && (r.guiOpened || r.constructionProduced > 0)) {
      _gatheredItems = true;
    }
    return r;
  },
  anvilDrive: async (filter = 'plate_iron', radius = 32) => await drone.driveAnvil(filter, radius),
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
  transportPlayer: async (x, y, z) => await drone.carryPlayer(x, y, z),
  setBase: (x, y, z) => world.setBase(x, y, z),
  saveLocation: (name, x, y, z, tags) => world.saveLocation(name, x, y, z, tags),
  getLocation: (name) => world.getLocation(name),
  listLocations: () => world.listLocations(),
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
  deliverToPlayer: async (filter = 'all', count = 512, playerName) => await drone.giveToPlayer(filter, count, playerName),
  findItemInChests: (filter = 'all', radius = 32) => world.findItemInChests(filter, radius),
  searchChests: (filter = 'all', radius = 32) => world.findItemInChests(filter, radius),
  findItem: (filter = 'all', radius = 32) => world.findItem(filter, radius),
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
  torch: async (enabled) => { await _flushBatches(); return await rpc('torch', typeof enabled === 'boolean' ? { enabled } : (_isObj(enabled) ? enabled : {})); },
  follow: async (target) => { await _flushBatches(); return await rpc('follow', _isObj(target) ? target : (target ? { target } : {})); },
  stay: async () => { await _flushBatches(); _handledItems = true; return await rpc('stay', {}); },
  stop: async () => await drone.stay(),
  come: async () => {
    await _flushBatches();
    const r = await rpc('come', {});
    const st = await _waitIdle(20000);
    return { ...r, ...st };
  },
  guard: async (target, filter) => { await _flushBatches(); return await rpc('guard', _isObj(target) ? target : { ...(target ? { target } : {}), ...(filter ? { filter } : {}) }); },
  cover: async (target, filter) => await drone.guard(target, filter),
  patrol: async (radius = 36, filter = 'hostile|mutant') => {
    await _flushBatches();
    const r = await rpc('patrol', _isObj(radius) ? radius : (typeof radius === 'string' ? { filter: radius, radius: typeof filter === 'number' ? filter : 36 } : { radius, filter }));
    // Awaitable: resolve once PATROL is reached (or immediately if the RPC
    // fails), so `await drone.patrol()` sequences with the next command.
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
