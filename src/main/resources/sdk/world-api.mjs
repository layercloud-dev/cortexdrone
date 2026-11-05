const world = {
  getBlock: (x, y, z) => Array.isArray(x) ? rpc('get_block', { x: x[0], y: x[1], z: x[2] }) : (_isObj(x) ? rpc('get_block', x) : rpc('get_block', { x, y, z })),
  getBlocksInBox: (x1, y1, z1, x2, y2, z2, includeAir = false) => _isObj(x1) ? rpc('get_blocks_in_box', x1) : rpc('get_blocks_in_box', { x1, y1, z1, x2, y2, z2, includeAir }),
  scanRoom: (x, y, z, maxRadius = 18) => _isObj(x) ? rpc('scan_room', x) : rpc('scan_room', { ...(typeof x === 'number' ? { x, y, z } : {}), maxRadius: (typeof x === 'number' && y === undefined) ? x : maxRadius }),
  scanBlocks: async (filter = 'ores', radius = 24, x, y, z) => {
    if (typeof filter === 'function') {
      const fn = filter;
      const m = String(fn).match(/['"]([a-zA-Z0-9_ :|-]{2,32})['"]/g);
      const hint = m ? m.map(s => s.slice(1, -1)).filter(s => !['name','registryName','string','object','number','boolean'].includes(s)).join('|') : 'all_non_air';
      const args = { filter: hint || 'all_non_air', radius, ...(typeof x === 'number' ? { x, y, z } : {}) };
      let r = await rpc('scan_blocks', args, { raw: true });
      if (_isPendingScan(r)) r = await _awaitScanJob(r.jobId, radius);
      else r = _normalizeScanResult(r, radius);
      if (r && Array.isArray(r.blocks)) { r.blocks = r.blocks.filter(fn); r.count = r.blocks.length; }
      return r;
    }
    const args = _isObj(filter) ? filter : { filter, radius, ...(typeof x === 'number' ? { x, y, z } : {}) };
    const fallbackRadius = typeof args.radius === 'number' ? args.radius : 24;
    let r = await rpc('scan_blocks', args, { raw: true });
    if (_isPendingScan(r)) return _awaitScanJob(r.jobId, fallbackRadius);
    if (!r || !Array.isArray(r.blocks)) return _normalizeScanResult(r, fallbackRadius);
    return _normalizeScanResult(r, fallbackRadius);
  },
  pollScan: async (jobId, timeoutMs) => {
    const id = typeof jobId === 'object' && jobId ? jobId.jobId : jobId;
    const fallback = typeof jobId === 'object' && jobId && typeof jobId.radius === 'number' ? jobId.radius : 0;
    return _awaitScanJob(id, fallback, typeof timeoutMs === 'number' ? timeoutMs : _SCAN_POLL_TIMEOUT_MS);
  },
  findDistant: async (target, maxRadius = 1200) => {
    const args = _isObj(target) ? target : { target, maxRadius };
    if (args.chestFirst !== false && !args.chestOnly) {
      try {
        const chestR = await rpc('find_item_in_chests', { filter: String(args.target || 'all'), radius: 32 });
        if (chestR && chestR.found && chestR.bestChest) {
          return { found: true, count: chestR.totalMatchingItems || 1, x: chestR.bestChest.x, y: chestR.bestChest.y, z: chestR.bestChest.z, distance: chestR.bestChest.dist, chest: true, blocks: [] };
        }
      } catch (e) { /* fall through to block sweep */ }
    }
    if (args.chestOnly) {
      const chestR = await rpc('find_item_in_chests', { filter: String(args.target || 'all'), radius: 32 });
      return { found: !!(chestR && chestR.found), count: (chestR && chestR.totalMatchingItems) || 0, blocks: [], chestOnly: true };
    }
    return rpc('find_distant', args);
  },
  getEntities: async (radius = 64, filter = 'all') => {
    if (typeof radius === 'function' || typeof filter === 'function') {
      const fn = typeof radius === 'function' ? radius : filter;
      const rad = typeof radius === 'number' ? radius : 64;
      const r = await rpc('get_entities', { radius: rad, filter: 'all' }, { raw: true });
      if (r && Array.isArray(r.entities)) { r.entities = r.entities.filter(fn); r.count = r.entities.length; }
      return r;
    }
    return _isObj(radius) ? rpc('get_entities', radius) : (typeof radius === 'string' ? rpc('get_entities', { filter: radius, radius: typeof filter === 'number' ? filter : 64 }) : rpc('get_entities', { radius, filter }));
  },
  getDrops: (radius = 32, filter = 'all') => _isObj(radius) ? rpc('get_drops', radius) : (typeof radius === 'string' ? rpc('get_drops', { filter: radius, radius: typeof filter === 'number' ? filter : 32 }) : rpc('get_drops', { radius, filter })),
  getRecipes: (filter = 'chest') => _isObj(filter) ? rpc('get_recipes', filter) : rpc('get_recipes', { filter }),
  getTopY: (x, z) => _isObj(x) ? rpc('get_top_y', x) : rpc('get_top_y', { x, z }),
  findItemInChests: (filter = 'all', radius = 32) =>
    _isObj(filter) ? rpc('find_item_in_chests', filter) : rpc('find_item_in_chests', { filter: String(filter), radius }),
  findItem: (filter = 'all', radius = 32) =>
    _isObj(filter) ? rpc('find_item', filter) : rpc('find_item', { filter: String(filter), radius }),
  scanChests: (radiusOrFilter = 24, filter = 'all') =>
    _isObj(radiusOrFilter)
      ? rpc('scan_chests', radiusOrFilter)
      : (typeof radiusOrFilter === 'string'
        ? rpc('scan_chests', { filter: radiusOrFilter, radius: typeof filter === 'number' ? filter : 28 })
        : rpc('scan_chests', { radius: radiusOrFilter, filter })),
  getChest: (x, y, z) => Array.isArray(x) ? rpc('get_chest', { x: x[0], y: x[1], z: x[2] }) : (_isObj(x) ? rpc('get_chest', x) : rpc('get_chest', { x, y, z })),
  scanFurnaces: (radius = 24) => _isObj(radius) ? rpc('scan_furnaces', radius) : rpc('scan_furnaces', { radius }),
  getMachineRecipes: (filter = '', machine = '') => _isObj(filter) ? rpc('get_machine_recipes', filter) : rpc('get_machine_recipes', { ...(filter ? { filter } : {}), ...(machine ? { machine } : {}) }),
  getMachineRecipe: (filter = 'plate_iron', machine = '', radius = 32) => _isObj(filter) ? rpc('get_machine_recipe', filter) : rpc('get_machine_recipe', { filter, ...(machine ? { machine } : {}), radius }),
  scanMachines: (machine = '', radius = 32) => _isObj(machine) ? rpc('scan_machines', machine) : rpc('scan_machines', { ...(machine ? { machine } : {}), radius }),
  machineIngredients: (filter = 'plate_iron', machine = '', radius = 32) => _isObj(filter) ? rpc('machine_ingredients', filter) : rpc('machine_ingredients', { filter, ...(machine ? { machine } : {}), radius }),
  scanVeins: (filter = 'ores', radius = 28, x, y, z) => _isObj(filter) ? rpc('scan_veins', filter) : rpc('scan_veins', { filter, radius, ...(typeof x === 'number' ? { x, y, z } : {}) }),
  findBase: () => rpc('find_base', {}),
  setBase: (x, y, z) => _isObj(x) ? rpc('set_base', x) : (typeof x === 'number' ? rpc('set_base', { x, y, z }) : rpc('set_base', {})),
  saveLocation: (name = 'base', x, y, z, tags = '') => _isObj(name) ? rpc('save_location', name) : rpc('save_location', { name, ...(typeof x === 'number' ? { x, y, z } : {}), ...(typeof x === 'string' ? { tags: x } : { tags }) }),
  getLocation: (name = 'base') => _isObj(name) ? rpc('get_location', name) : rpc('get_location', { name }),
  listLocations: () => rpc('list_locations', {}),
  deleteLocation: (name) => _isObj(name) ? rpc('delete_location', name) : rpc('delete_location', { name }),
  findBuildSite: (width = 5, depth = 5, maxRadius = 24) =>
    _isObj(width) ? rpc('find_build_site', width) : rpc('find_build_site', { width, depth: depth || width, maxRadius }),
  planStructure: (spec = {}) => rpc('plan_structure', _isObj(spec) ? spec : { style: String(spec) })
};

const player = {
  get: (name) => _isObj(name) ? rpc('get_player', name) : rpc('get_player', name ? { name } : {}),
  getInventory: (name) => _isObj(name) ? rpc('get_player_inventory', name) : rpc('get_player_inventory', name ? { name } : {}),
  raycast: (maxDist = 80) => _isObj(maxDist) ? rpc('raycast_player', maxDist) : rpc('raycast_player', { maxDist })
};
