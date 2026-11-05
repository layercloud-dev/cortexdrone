// ---------------------------------------------------------------------------
// Master tool surface. There are no monolithic "mine/harvest/build/farm" tools.
// Every physical behavior is composed by the AI from:
//   inspect -> moveTo (navigator chooses fly/tunnel/up route) -> break/place/
//   interact/use -> repeat.
// Every primitive waits until its own physical action finishes.
// ---------------------------------------------------------------------------
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

  // route: 'auto' (fly, dig self-rescue when blocked), 'tunnel' (carve a
  // corridor), 'up' (route through the surface first), 'direct' (fly only).
  // Resolves only after the navigator arrives or the route gives up.
  moveTo: async (x, y, z, opts = {}) => {
    let args;
    if (_isObj(x)) args = { ...x };
    else if (Array.isArray(x)) args = { x: x[0], y: x[1], z: x[2] };
    else args = { x, y, z };
    if (opts && _isObj(opts)) Object.assign(args, opts);
    if (!args.route) args.route = 'auto';
    const r = await rpc('navigate', args);
    if (!r || r.ok === false) return r;
    const maxWaitMs = Number(args.maxWaitMs || 60000);
    const st = await _waitUntil((s) => s.mode !== 'GOTO' || s.arrived === true, maxWaitMs);
    return { ...r, ...st, arrived: st.mode !== 'GOTO' || st.arrived === true };
  },

  // Break exactly one explicit block. If out of reach the primitive returns
  // the navigator's standoff; the SDK moves there, then calls the break again.
  breakBlock: async (x, y, z, opts = {}) => {
    const target = _isObj(x) ? { ...x } : (Array.isArray(x) ? { x: x[0], y: x[1], z: x[2] } : { x, y, z });
    const route = (opts && opts.route) || 'auto';
    const maxWaitMs = Number((opts && opts.maxWaitMs) || 120000);
    for (let attempt = 0; attempt < 4; attempt++) {
      const r = await rpc('break_block', target);
      if (r && r.needsMovement && r.standoff) {
        const mv = await drone.moveTo(r.standoff, { route, maxWaitMs });
        if (!mv || mv.ok === false) return mv || r;
        continue;
      }
      if (r && r.ok === false && /standoff/i.test(String(r.error || ''))) {
        // Fully buried target with no exposed standoff: carve toward it, then
        // retry the single-block break once the navigator has arrived.
        const mv = await drone.moveTo({ x: target.x, y: target.y, z: target.z, route: 'tunnel', maxWaitMs });
        if (!mv || mv.ok === false) return { ...r, movement: mv };
        continue;
      }
      if (r && r.started) {
        const st = await _waitUntil((s) => s.mode !== 'MINE' && !s.activeBreakTarget, maxWaitMs);
        return { ...r, ...st, broken: !st.activeBreakTarget };
      }
      return r;
    }
    return { ok: false, error: 'Could not reach break target after navigation attempts' };
  },

  // Place exactly one explicit block from drone/chest/player inventory.
  placeBlock: async (x, y, z, block = 'any', meta = -1, opts = {}) => {
    let target;
    if (_isObj(x)) target = { ...x };
    else if (Array.isArray(x)) target = { x: x[0], y: x[1], z: x[2], block: y || 'any', meta: z ?? -1 };
    else target = { x, y, z, block, meta };
    if (opts && _isObj(opts)) Object.assign(target, opts);
    for (let attempt = 0; attempt < 3; attempt++) {
      const r = await rpc('place_block', target);
      if (r && r.needsMovement && r.standoff) {
        const mv = await drone.moveTo(r.standoff, { route: target.route || 'auto' });
        if (!mv || mv.ok === false) return mv || r;
        continue;
      }
      return r;
    }
    return { ok: false, error: 'Could not reach placement target' };
  },

  interactBlock: async (x, y, z, side, opts = {}) => {
    let target;
    if (_isObj(x)) target = { ...x };
    else if (Array.isArray(x)) target = { x: x[0], y: x[1], z: x[2], ...(typeof side === 'number' ? { side } : {}) };
    else target = { x, y, z, ...(typeof side === 'number' ? { side } : {}) };
    if (opts && _isObj(opts)) Object.assign(target, opts);
    for (let attempt = 0; attempt < 3; attempt++) {
      const r = await rpc('interact_block', target);
      if (r && r.needsMovement && r.standoff) {
        const mv = await drone.moveTo(r.standoff, { route: target.route || 'auto' });
        if (!mv || mv.ok === false) return mv || r;
        continue;
      }
      return r;
    }
    return { ok: false, error: 'Could not reach interaction target' };
  },

  // Uses an item (hoe, bucket, food, tool...). Accepts an object, a slot, or
  // an item filter plus optional target block coordinates.
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
        const mv = await drone.moveTo(r.standoff, { route: target.route || 'auto' });
        if (!mv || mv.ok === false) return mv || r;
        continue;
      }
      return r;
    }
    return { ok: false, error: 'Could not reach item-use target' };
  },

  // Generic inventory primitive. op: move | give | take | drop | withdraw | deposit | suck
  transfer: async (opOrOpts, args = {}) => {
    const r = _isObj(opOrOpts)
      ? await rpc('transfer', opOrOpts)
      : await rpc('transfer', { ...( _isObj(args) ? args : {} ), op: String(opOrOpts || 'move') });
    if (r && (r.flyingToPlayer || r.flyingToChest || r.queued > 0 || r.mode === 'DROP' || r.mode === 'DEPOSIT')) {
      const st = await _waitIdle(60000);
      return { ...r, ...st };
    }
    return r;
  },

  craft: async (filter = 'planks', count = 1) => {
    return await rpc('craft', _isObj(filter) ? filter : { filter, count });
  },

  // Visual/audio/chat effect primitive. kind: led | ledEffect | particles | sound | note | status | say
  effect: async (kind = 'led', args = {}) => {
    if (_isObj(kind)) return await rpc('effect', kind);
    return await rpc('effect', { ...( _isObj(args) ? args : {} ), kind: String(kind) });
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
