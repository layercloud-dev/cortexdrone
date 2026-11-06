//noinspection JSUnresolvedReference,JSUnusedGlobalSymbols
// ---------------------------------------------------------------------------
// Tool surface. Every tool is a single explicit operation and maps 1:1 to one
// RPC method; there is no `inspect(op)` / `visuals(kind)` dispatcher. Every
// physical primitive waits until its own action finishes.
// ---------------------------------------------------------------------------
const _awaitMovement = async (r, args = {}) => {
  if (!r || r.ok === false) return r;
  const maxWaitMs = Number((args && args.maxWaitMs) || 120000);
  const st = await _waitUntil((s) => s.navigationActive === false, maxWaitMs);
  const failed = !!st.navigationError;
  return { ...r, ...st, arrived: st.navigationActive === false && !failed };
};

const _point = (x, y, z) => {
  if (_isObj(x)) return { ...x };
  if (Array.isArray(x)) return { x: x[0], y: x[1], z: x[2] };
  return { x, y, z };
};

const drone = {
  // --- session -------------------------------------------------------------
  state: (opts = {}) => rpc('state', _isObj(opts) ? opts : {}),

  cancel: () => rpc('cancel', { loopId: typeof LOOP_ID === 'number' ? LOOP_ID : 0 }),

  // --- navigation ----------------------------------------------------------
  goto: async (x, y, z, opts = {}) => {
    let args;
    if (_isObj(x)) args = { ...x };
    else if (Array.isArray(x)) args = { x: x[0], y: x[1], z: x[2] };
    else args = { x, y, z };
    if (opts && _isObj(opts)) Object.assign(args, opts);
    return _awaitMovement(await rpc('goto', args), args);
  },

  stop: async () => rpc('stop', {}),

  getBreadcrumbs: async (opts = {}) => rpc('get_breadcrumbs', _isObj(opts) ? opts : {}),

  // --- blocks --------------------------------------------------------------
  // Break exactly one explicit block. If out of reach the tool returns the
  // navigator's standoff; the SDK moves there, then calls the break again.
  breakBlock: async (x, y, z, opts = {}) => {
    const target = _point(x, y, z);
    const maxWaitMs = Number((opts && opts.maxWaitMs) || 120000);
    for (let attempt = 0; attempt < 4; attempt++) {
      const r = await rpc('break_block', target);
      if (r && r.needsMovement && r.standoff) {
        //noinspection JSCheckFunctionSignatures
        const mv = await drone.goto(r.standoff, { maxWaitMs });
        if (!mv || mv.ok === false) return mv || r;
        continue;
      }
      if (r && r.ok === false && /standoff/i.test(String(r.error || ''))) {
        // Fully buried target with no exposed standoff: carve toward it, then
        // retry the single-block break once the navigator has arrived.
        const mv = await drone.goto({ x: target.x, y: target.y, z: target.z, maxWaitMs });
        if (!mv || mv.ok === false) return { ...r, movement: mv };
        continue;
      }
      return r;
    }
    return { ok: false, error: 'Could not reach break target after navigation attempts' };
  },

  // Place exactly one explicit block from the drone inventory.
  placeBlock: async (x, y, z, block = 'any', meta = -1, opts = {}) => {
    let target;
    if (_isObj(x)) target = { ...x };
    else if (Array.isArray(x)) target = { x: x[0], y: x[1], z: x[2], block: y || 'any', meta: z ?? -1 };
    else target = { x, y, z, block, meta };
    if (opts && _isObj(opts)) Object.assign(target, opts);
    for (let attempt = 0; attempt < 3; attempt++) {
      const r = await rpc('place_block', target);
      if (r && r.needsMovement && r.standoff) {
        //noinspection JSCheckFunctionSignatures
        const mv = await drone.goto(r.standoff);
        if (!mv || mv.ok === false) return mv || r;
        continue;
      }
      return r;
    }
    return { ok: false, error: 'Could not reach placement target' };
  },

  // --- interaction ---------------------------------------------------------
  interactBlock: async (x, y, z, side, opts = {}) => {
    let target;
    if (_isObj(x)) target = { ...x };
    else if (Array.isArray(x)) target = { x: x[0], y: x[1], z: x[2], ...(typeof side === 'number' ? { side } : {}) };
    else target = { x, y, z, ...(typeof side === 'number' ? { side } : {}) };
    if (opts && _isObj(opts)) Object.assign(target, opts);
    for (let attempt = 0; attempt < 3; attempt++) {
      const r = await rpc('interact_block', target);
      if (r && r.needsMovement && r.standoff) {
        //noinspection JSCheckFunctionSignatures
        const mv = await drone.goto(r.standoff);
        if (!mv || mv.ok === false) return mv || r;
        continue;
      }
      return r;
    }
    return { ok: false, error: 'Could not reach interaction target' };
  },

  // One held-item use, optionally on a block target.
  useItem: async (a = 0, b, c, d2, e) => {
    let target;
    if (_isObj(a)) target = { ...a };
    else if (typeof a === 'number') {
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
    for (let attempt = 0; attempt < 3; attempt++) {
      const r = await rpc('use_item', target);
      if (r && r.needsMovement && r.standoff) {
        //noinspection JSCheckFunctionSignatures
        const mv = await drone.goto(r.standoff);
        if (!mv || mv.ok === false) return mv || r;
        continue;
      }
      return r;
    }
    return { ok: false, error: 'Could not reach item-use target' };
  },

  // Exactly one melee attack with the selected/held item.
  attackEntity: async (entityId, opts = {}) => {
    const target = _isObj(entityId) ? { ...entityId } : { entityId: Number(entityId) };
    if (opts && _isObj(opts)) Object.assign(target, opts);
    for (let attempt = 0; attempt < 3; attempt++) {
      const r = await rpc('attack_entity', target);
      if (r && r.needsMovement && r.standoff) {
        //noinspection JSCheckFunctionSignatures
        const mv = await drone.goto(r.standoff);
        if (!mv || mv.ok === false) return mv || r;
        continue;
      }
      return r;
    }
    return { ok: false, error: 'Could not reach attack target' };
  },

  // Mount one entity (including a player) onto the drone.
  pickEntity: async (entityId, opts = {}) => {
    const target = _isObj(entityId)
      ? { ...entityId }
      : { entityId: Number(entityId) };
    if (opts && _isObj(opts)) Object.assign(target, opts);
    for (let attempt = 0; attempt < 3; attempt++) {
      const r = await rpc('pick_entity', target);
      if (r && r.needsMovement && r.standoff) {
        //noinspection JSCheckFunctionSignatures
        const mv = await drone.goto(r.standoff);
        if (!mv || mv.ok === false) return mv || r;
        continue;
      }
      return r;
    }
    return { ok: false, error: 'Could not reach pick target' };
  },

  // Dismount the current rider, or the explicit entityId when provided.
  unpickEntity: async (opts = {}) => {
    return await rpc('unpick_entity', _isObj(opts) ? opts : { entityId: Number(opts) });
  },

  // --- inventory transfers -------------------------------------------------
  moveItem: async (opts = {}) => {
    if (!_isObj(opts)) throw new Error('moveItem requires an options object');
    const r = await rpc('move_item', opts);
    return await _awaitTransfer(r);
  },

  giveToPlayer: async (opts = {}) => await _awaitTransfer(await rpc('give_to_player', _isObj(opts) ? opts : {})),

  takeFromPlayer: async (opts = {}) => await _awaitTransfer(await rpc('take_from_player', _isObj(opts) ? opts : {})),

  withdrawChest: async (opts = {}) => await _awaitTransfer(await rpc('withdraw_chest', _isObj(opts) ? opts : {})),

  depositChest: async (opts = {}) => await _awaitTransfer(await rpc('deposit_chest', _isObj(opts) ? opts : {})),

  dropItems: async (opts = {}) => await _awaitTransfer(await rpc('drop_items', _isObj(opts) ? opts : {})),

  suckItems: async (opts = {}) => await _awaitTransfer(await rpc('suck_items', _isObj(opts) ? opts : {})),

  craft: async (filter = 'planks', count = 1) => {
    return await rpc('craft', _isObj(filter) ? filter : { filter, count });
  },

  // --- visuals -------------------------------------------------------------
  setLed: async (opts = {}) => rpc('set_led', _isObj(opts) ? opts : { color: Number(opts) }),

  setLight: async (opts = {}) => rpc('set_light', _isObj(opts) ? opts : { enabled: !!opts }),

  spawnParticles: async (opts = {}) => rpc('spawn_particles', _isObj(opts) ? opts : {}),

  playSound: async (opts = {}) => rpc('play_sound', _isObj(opts) ? opts : { sound: String(opts) }),

  playNote: async (opts = {}) => rpc('play_note', _isObj(opts) ? opts : { semitone: Number(opts) }),

  say: async (message) => rpc('say', _isObj(message) ? message : { message: String(message || '') }),

  setStatusText: async (text) => rpc('set_status_text', _isObj(text) ? text : { text: String(text || '') }),

  // --- skills --------------------------------------------------------------
  searchSkills: async (query = '', opts = {}) => {
    const args = _isObj(query) ? { ...query } : { ...(_isObj(opts) ? opts : {}), query: String(query) };
    return await rpc('search_skills', args);
  },

  listSkills: async (opts = {}) => rpc('list_skills', _isObj(opts) ? opts : {}),

  saveSkill: async (opts = {}) => rpc('save_skill', _isObj(opts) ? opts : {}),

  // --- world inspection ----------------------------------------------------
  getBlock: async (x, y, z, opts = {}) => {
    const args = _point(x, y, z);
    if (opts && _isObj(opts)) Object.assign(args, opts);
    return await rpc('get_block', args);
  },

  getBlocksInBox: async (opts = {}) => rpc('get_blocks_in_box', _isObj(opts) ? opts : {}),

  getTopY: async (x, z, opts = {}) => {
    const args = _isObj(x) ? { ...x } : { x, z };
    if (opts && _isObj(opts)) Object.assign(args, opts);
    return await rpc('get_top_y', args);
  },

  scanRoom: async (opts = {}) => rpc('scan_room', _isObj(opts) ? opts : {}),

  // Matching blocks in a radius. Large radius is time-sliced and auto-polled.
  scanBlocks: async (opts = {}) => {
    const request = _isObj(opts) ? { ...opts } : { filter: String(opts) };
    const r = await rpc('scan_blocks', request, { raw: true });
    if (_isPendingScan(r)) return _awaitScanJob(r.jobId, request.radius || 24);
    return _normalizeScanResult(r, request.radius || 24);
  },

  scanVeins: async (opts = {}) => rpc('scan_veins', _isObj(opts) ? opts : { filter: String(opts) }),

  findDistant: async (target, opts = {}) => {
    const args = _isObj(target) ? { ...target } : { ...(_isObj(opts) ? opts : {}), target: String(target) };
    return await rpc('find_distant', args);
  },

  getEntities: async (opts = {}) => rpc('get_entities', _isObj(opts) ? opts : { filter: String(opts) }),

  getDrops: async (opts = {}) => rpc('get_drops', _isObj(opts) ? opts : { filter: String(opts) }),

  raycastPlayer: async (opts = {}) => rpc('raycast_player', _isObj(opts) ? opts : { maxDist: Number(opts) || 80 }),

  lookupDoc: async (query, opts = {}) => {
    const args = _isObj(query) ? { ...query } : { ...(_isObj(opts) ? opts : {}), query: String(query) };
    return await rpc('lookup_doc', args);
  },

  findBuildSite: async (opts = {}) => rpc('find_build_site', _isObj(opts) ? opts : {}),

  planStructure: async (opts = {}) => rpc('plan_structure', _isObj(opts) ? opts : {}),

  // --- inventory inspection ------------------------------------------------
  getPlayer: async (opts = {}) => rpc('get_player', _isObj(opts) ? opts : {}),

  getPlayerInventory: async (opts = {}) => rpc('get_player_inventory', _isObj(opts) ? opts : {}),

  getChest: async (x, y, z, opts = {}) => {
    const args = _point(x, y, z);
    if (opts && _isObj(opts)) Object.assign(args, opts);
    return await rpc('get_chest', args);
  },

  scanChests: async (opts = {}) => rpc('scan_chests', _isObj(opts) ? opts : { filter: String(opts) }),

  findItemInChests: async (filter, opts = {}) => {
    const args = _isObj(filter) ? { ...filter } : { ...(_isObj(opts) ? opts : {}), filter: String(filter) };
    return await rpc('find_item_in_chests', args);
  },

  findItem: async (filter, opts = {}) => {
    const args = _isObj(filter) ? { ...filter } : { ...(_isObj(opts) ? opts : {}), filter: String(filter) };
    return await rpc('find_item', args);
  },

  getRecipes: async (filter = 'chest', opts = {}) => {
    const args = _isObj(filter) ? { ...filter } : { ...(_isObj(opts) ? opts : {}), filter: String(filter) };
    return await rpc('get_recipes', args);
  },

  scanFurnaces: async (opts = {}) => rpc('scan_furnaces', _isObj(opts) ? opts : {}),

  getMachineRecipes: async (filter = '', opts = {}) => {
    const args = _isObj(filter) ? { ...filter } : { ...(_isObj(opts) ? opts : {}), filter: String(filter) };
    return await rpc('get_machine_recipes', args);
  },

  getMachineRecipe: async (filter, opts = {}) => {
    const args = _isObj(filter) ? { ...filter } : { ...(_isObj(opts) ? opts : {}), filter: String(filter) };
    return await rpc('get_machine_recipe', args);
  },

  scanMachines: async (opts = {}) => rpc('scan_machines', _isObj(opts) ? opts : {}),

  machineIngredients: async (filter, opts = {}) => {
    const args = _isObj(filter) ? { ...filter } : { ...(_isObj(opts) ? opts : {}), filter: String(filter) };
    return await rpc('machine_ingredients', args);
  }
};

async function _awaitTransfer(r) {
  if (r && (r.flyingToPlayer || r.flyingToChest || r.queued > 0)) {
    const st = await _waitIdle(60000);
    return { ...r, ...st };
  }
  return r;
}

// Single canonical global. No alias objects, no second tool name.
globalThis.drone = drone;
