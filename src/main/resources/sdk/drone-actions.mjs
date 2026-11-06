//noinspection JSUnresolvedReference,JSUnusedGlobalSymbols
// ---------------------------------------------------------------------------
// Master tool surface. There are no monolithic "mine/harvest/build/farm" tools.
// Every physical behavior is composed by the AI from:
//   inspect -> goto (navigation manager chooses trail/flight/dig) -> break/place/
//   interact/use -> repeat.
// Every primitive waits until its own physical action finishes.
// ---------------------------------------------------------------------------
const _awaitMovement = async (r, args = {}) => {
  if (!r || r.ok === false) return r;
  const maxWaitMs = Number((args && args.maxWaitMs) || 120000);
  const st = await _waitUntil((s) => s.navigationActive === false, maxWaitMs);
  const failed = !!st.navigationError;
  return { ...r, ...st, arrived: st.navigationActive === false && !failed };
};

const drone = {
  state: (opts = {}) => rpc('state', _isObj(opts) ? opts : {}),

  inspect: async (op, args = {}) => {
    let request;
    if (_isObj(op)) { request = { ...op }; }
    else { request = { ...( _isObj(args) ? args : {} ), op: String(op || 'block') }; }
    const key = String(request.op || 'block').toLowerCase();
    let r = await rpc('inspect', request, { raw: true });
    if (_isPendingScan(r)) return _awaitScanJob(r.jobId, request.radius || 24);
    if (key === 'blocks' || key === 'scanblocks') {
      return _normalizeScanResult(r, request.radius || 24);
    }
    return r;
  },

  // Goal-driven navigation. The manager chooses breadcrumbs, a no-dig flight
  // path, or an internal dig route. It resolves after arrival/failure.
  goto: async (x, y, z, opts = {}) => {
    let args;
    if (_isObj(x)) args = { ...x };
    else if (Array.isArray(x)) args = { x: x[0], y: x[1], z: x[2] };
    else args = { x, y, z };
    if (opts && _isObj(opts)) Object.assign(args, opts);
    return _awaitMovement(await rpc('goto', args), args);
  },

  stop: async () => rpc('stop', {}),

  // Break exactly one explicit block. If out of reach the primitive returns
  // the navigator's standoff; the SDK moves there, then calls the break again.
  breakBlock: async (x, y, z, opts = {}) => {
    const target = _isObj(x) ? { ...x } : (Array.isArray(x) ? { x: x[0], y: x[1], z: x[2] } : { x, y, z });
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
        //noinspection JSCheckFunctionSignatures
        const mv = await drone.goto({ x: target.x, y: target.y, z: target.z, maxWaitMs });
        if (!mv || mv.ok === false) return { ...r, movement: mv };
        continue;
      }
      return r;
    }
    return { ok: false, error: 'Could not reach break target after navigation attempts' };
  },

  // Place exactly one explicit block from drone/chest/player inventory.
  //noinspection JSUnusedGlobalSymbols
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

  //noinspection JSUnusedGlobalSymbols
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

  // Uses an item (hoe, bucket, food, tool...). Accepts an object, a slot, or
  // an item filter plus optional target block coordinates. With entityId/attack
  // it performs exactly one melee attack and moves into reach if needed.
  //noinspection JSUnusedGlobalSymbols
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

  // Mount one entity (including a player) onto the drone. Moves into reach
  // first when the entity is out of range. Compose transport as:
  // pickEntity -> goto -> unpickEntity.
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

  // Generic inventory primitive. op: move | give | take | drop | withdraw | deposit | suck
  transfer: async (opOrOpts, args = {}) => {
    const r = _isObj(opOrOpts)
      ? await rpc('transfer', opOrOpts)
      : await rpc('transfer', { ...( _isObj(args) ? args : {} ), op: String(opOrOpts || 'move') });
    if (r && (r.flyingToPlayer || r.flyingToChest || r.queued > 0)) {
      const st = await _waitIdle(60000);
      return { ...r, ...st };
    }
    return r;
  },

  craft: async (filter = 'planks', count = 1) => {
    return await rpc('craft', _isObj(filter) ? filter : { filter, count });
  },

  // Visual/audio/chat primitive. kind: led | light | particles | sound | note | status | say
  visuals: async (kind = 'led', args = {}) => {
    if (_isObj(kind)) return await rpc('visuals', kind);
    return await rpc('visuals', { ...( _isObj(args) ? args : {} ), kind: String(kind) });
  },

  // Optional reusable-skill memory. op: search | list | save
  skills: async (op = 'search', args = {}) => {
    if (_isObj(op)) return await rpc('skills', op);
    return await rpc('skills', { ...( _isObj(args) ? args : {} ), op: String(op) });
  },

  cancel: () => rpc('cancel', { loopId: typeof LOOP_ID === 'number' ? LOOP_ID : 0 })
};

// Single canonical global. No alias objects, no second tool name.
globalThis.drone = drone;
