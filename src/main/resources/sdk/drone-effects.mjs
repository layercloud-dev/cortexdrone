Object.assign(drone, {
  hsvToRgb,
  setLed: (color, g, b) => {
    if (typeof color === 'number' && typeof g === 'number' && typeof b === 'number') {
      return rpc('set_led', { r: color, g, b });
    }
    if (Array.isArray(color)) {
      return rpc('set_led', { r: color[0] || 0, g: color[1] || 0, b: color[2] || 0 });
    }
    if (_isObj(color)) {
      return rpc('set_led', color);
    }
    return rpc('set_led', { color });
  },
  ledEffect: (effect = 'rainbow') => _isObj(effect) ? rpc('led_effect', effect) : rpc('led_effect', { effect: String(effect) }),
  rainbow: async (durationMs = 5000, stepMs = 80, keepOn = true) => {
    const dur = Math.min(30000, Math.max(300, Number(durationMs) || 5000));
    const step = Math.max(40, Number(stepMs) || 80);
    const t0 = Date.now();
    let hue = 0;
    while (Date.now() - t0 < dur) {
      await rpc('set_led', { color: hsvToRgb(hue, 1, 1) });
      hue = (hue + 24) % 360;
      await sleep(step);
    }
    if (keepOn) {
      await rpc('led_effect', { effect: 'RAINBOW' });
    }
    return { ok: true, effect: keepOn ? 'RAINBOW' : 'DONE', durationMs: Date.now() - t0 };
  },
  particles: (type = 'heart', count = 12, spread = 0.35, speed = 0.05) =>
    _isObj(type) ? rpc('spawn_particles', type) : rpc('spawn_particles', { type, count, spread, speed }),
  spawnParticles: (type = 'heart', count = 12, spread = 0.35, speed = 0.05) => drone.particles(type, count, spread, speed),
  playSound: (sound = 'note.pling', volume = 1.0, pitch = 1.0) =>
    _isObj(sound) ? rpc('play_sound', sound) : rpc('play_sound', { sound, volume, pitch }),
  playNote: (instrument = 'pling', semitone = 12, volume = 1.0) =>
    _isObj(instrument)
      ? rpc('play_note', instrument)
      : rpc('play_note', { sound: String(instrument).includes('.') ? instrument : `note.${instrument}`, semitone, volume }),
  setVelocity: (vx = 0, vy = 0, vz = 0) =>
    Array.isArray(vx) ? rpc('set_velocity', { vx: vx[0], vy: vx[1], vz: vx[2] }) : (_isObj(vx) ? rpc('set_velocity', vx) : rpc('set_velocity', { vx, vy, vz })),
  moveOffset: async (dx = 0, dy = 0, dz = 0) => {
    const st = await rpc('get_drone', {});
    const off = Array.isArray(dx) ? { dx: dx[0], dy: dx[1], dz: dx[2] } : (_isObj(dx) ? dx : { dx, dy, dz });
    return await drone.flyTo((st.x || 0) + (off.dx || 0), (st.y || 0) + (off.dy || 0), (st.z || 0) + (off.dz || 0), 8000);
  },
  nudge: async (dx = 0, dy = 0, dz = 0) => await drone.moveOffset(dx, dy, dz),
  spin: async (durationMs = 2500) => {
    const st = await rpc('get_drone', {});
    const cx = st.x, cy = st.y, cz = st.z;
    const t0 = Date.now();
    let step = 0;
    while (Date.now() - t0 < Math.min(15000, durationMs)) {
      const ang = step * 0.85;
      await rpc('set_led', { color: hsvToRgb((step * 30) % 360, 1, 1) });
      await rpc('set_velocity', { vx: Math.cos(ang) * 0.45, vy: 0.04 * Math.sin(step), vz: Math.sin(ang) * 0.45 });
      if (step % 2 === 0) await rpc('spawn_particles', { type: 'fireworksSpark', count: 5, spread: 0.25 });
      step++;
      await sleep(90);
    }
    await drone.flyTo(cx, cy, cz, 4000);
    return { ok: true, spunMs: Date.now() - t0 };
  },
  bounce: async (times = 5, height = 1.6) => {
    const st = await rpc('get_drone', {});
    const cx = st.x, cy = st.y, cz = st.z;
    const n = Math.min(16, Math.max(1, Number(times) || 5));
    const h = Math.min(5.0, Math.max(0.5, Number(height) || 1.6));
    for (let i = 0; i < n; i++) {
      await rpc('set_led', { color: hsvToRgb((i * 60) % 360, 1, 1) });
      await rpc('play_note', { sound: 'note.pling', semitone: (i * 3) % 24 });
      await drone.flyTo(cx, cy + h, cz, 2500);
      await rpc('spawn_particles', { type: 'happyVillager', count: 8, spread: 0.35 });
      await drone.flyTo(cx, cy, cz, 2500);
    }
    return { ok: true, bounces: n };
  },
  circle: async (radius = 3.0, loops = 2, around = 'player') => {
    const p = around === 'player' ? await rpc('get_player', {}) : await rpc('get_drone', {});
    const cx = p.x || 0, cy = (p.y || 65) + (around === 'player' ? 1.8 : 0), cz = p.z || 0;
    const r = Math.min(12.0, Math.max(1.2, Number(radius) || 3.0));
    const totalLoops = Math.min(8, Math.max(1, Number(loops) || 2));
    const stepsPerLoop = 12;
    for (let i = 0; i <= totalLoops * stepsPerLoop; i++) {
      const ang = (i / stepsPerLoop) * Math.PI * 2;
      const tx = cx + Math.cos(ang) * r;
      const tz = cz + Math.sin(ang) * r;
      await rpc('set_led', { color: hsvToRgb((i * 25) % 360, 1, 1) });
      await rpc('move_to', { x: tx, y: cy + Math.sin(ang * 2) * 0.4, z: tz });
      if (i % 3 === 0) await rpc('spawn_particles', { type: 'reddust', count: 6, spread: 0.25 });
      await sleep(160);
    }
    return { ok: true, radius: r, loops: totalLoops };
  },
  figure8: async (radius = 3.2, loops = 2) => {
    const p = await rpc('get_player', {});
    const st = await rpc('get_drone', {});
    const cx = p.ok !== false ? p.x : st.x;
    const cy = (p.ok !== false ? p.y + 2.0 : st.y);
    const cz = p.ok !== false ? p.z : st.z;
    const r = Math.min(10.0, Math.max(1.5, Number(radius) || 3.2));
    const totalLoops = Math.min(6, Math.max(1, Number(loops) || 2));
    const stepsPerLoop = 16;
    for (let i = 0; i <= totalLoops * stepsPerLoop; i++) {
      const t = (i / stepsPerLoop) * Math.PI * 2;
      const tx = cx + Math.sin(t) * r;
      const tz = cz + Math.sin(t) * Math.cos(t) * r;
      const ty = cy + Math.cos(t * 2) * 0.5;
      await rpc('set_led', { color: hsvToRgb((i * 22) % 360, 1, 1) });
      await rpc('move_to', { x: tx, y: ty, z: tz });
      if (i % 2 === 0) await rpc('spawn_particles', { type: 'note', count: 3, spread: 0.3 });
      await sleep(150);
    }
    return { ok: true, pattern: 'figure8', loops: totalLoops };
  },
  spiral: async (radius = 2.5, height = 4.5, loops = 2) => {
    const st = await rpc('get_drone', {});
    const cx = st.x, cy = st.y, cz = st.z;
    const r = Math.min(8.0, Math.max(1.2, Number(radius) || 2.5));
    const h = Math.min(12.0, Math.max(1.5, Number(height) || 4.5));
    const totalSteps = 24;
    for (let i = 0; i <= totalSteps; i++) {
      const frac = i / totalSteps;
      const ang = frac * Math.PI * 2 * (Number(loops) || 2);
      const yOff = Math.sin(frac * Math.PI) * h;
      await rpc('set_led', { color: hsvToRgb(frac * 360, 1, 1) });
      await rpc('move_to', { x: cx + Math.cos(ang) * r, y: cy + yOff, z: cz + Math.sin(ang) * r });
      await rpc('spawn_particles', { type: 'fireworksSpark', count: 5, spread: 0.2 });
      await sleep(140);
    }
    return { ok: true, pattern: 'spiral' };
  },
  flip: async (radius = 2.2) => {
    const st = await rpc('get_drone', {});
    const cx = st.x, cy = st.y, cz = st.z;
    const r = Math.min(5.0, Math.max(1.2, Number(radius) || 2.2));
    const steps = 12;
    await rpc('play_sound', { sound: 'fireworks.launch', volume: 1.0, pitch: 1.2 });
    for (let i = 0; i <= steps; i++) {
      const ang = (i / steps) * Math.PI * 2;
      const tx = cx + Math.sin(ang) * r;
      const ty = cy + (1 - Math.cos(ang)) * r;
      await rpc('set_led', { color: hsvToRgb((i * 30) % 360, 1, 1) });
      await rpc('move_to', { x: tx, y: ty, z: cz });
      await rpc('spawn_particles', { type: 'flame', count: 4, spread: 0.15 });
      await sleep(110);
    }
    return { ok: true, pattern: 'flip' };
  },
  barrelRoll: async (radius = 2.2) => await drone.flip(radius),
  fireworks: async (bursts = 5) => {
    const n = Math.min(15, Math.max(1, Number(bursts) || 5));
    for (let i = 0; i < n; i++) {
      await rpc('set_led', { color: hsvToRgb((i * 72) % 360, 1, 1) });
      await rpc('play_sound', { sound: i % 2 === 0 ? 'fireworks.blast' : 'fireworks.twinkle', volume: 1.0, pitch: 0.9 + (i % 3) * 0.2 });
      await rpc('spawn_particles', { type: 'fireworksSpark', count: 25, spread: 0.8, speed: 0.15 });
      await rpc('spawn_particles', { type: 'heart', count: 6, spread: 0.6, speed: 0.05 });
      await sleep(350);
    }
    return { ok: true, bursts: n };
  },
  dance: async (durationMs = 6000) => {
    const p = await rpc('get_player', {});
    const st = await rpc('get_drone', {});
    const cx = p.ok !== false ? p.x : st.x;
    const cy = (p.ok !== false ? p.y + 2.0 : st.y);
    const cz = p.ok !== false ? p.z : st.z;
    const dur = Math.min(25000, Math.max(1500, Number(durationMs) || 6000));
    const notes = [0, 4, 7, 12, 16, 12, 7, 4, 2, 5, 9, 14, 17, 14, 9, 5];
    await rpc('led_effect', { effect: 'DISCO' });
    await rpc('set_status_text', { text: 'Dancing!\n♪ ♫ ♪' });
    const t0 = Date.now();
    let step = 0;
    while (Date.now() - t0 < dur) {
      const t = step * 0.45;
      const tx = cx + Math.sin(t) * 2.6;
      const tz = cz + Math.cos(t * 2) * 1.8;
      const ty = cy + Math.sin(step * 0.9) * 0.75;
      await rpc('set_led', { color: hsvToRgb((step * 28) % 360, 1, 1) });
      await rpc('move_to', { x: tx, y: ty, z: tz });
      await rpc('play_note', { sound: step % 4 === 0 ? 'note.bassattack' : 'note.pling', semitone: notes[step % notes.length], volume: 0.9 });
      await rpc('spawn_particles', { type: step % 3 === 0 ? 'heart' : (step % 2 === 0 ? 'note' : 'fireworksSpark'), count: 6, spread: 0.4 });
      step++;
      await sleep(180);
    }
    await rpc('led_effect', { effect: 'RAINBOW' });
    await rpc('follow', {});
    return { ok: true, dancedMs: Date.now() - t0, steps: step };
  }
});

const skills = {
  search: (query = '', limit = 5) => _isObj(query) ? rpc('search_skills', query) : rpc('search_skills', { query, limit }),
  list: () => rpc('list_skills', { limit: 25 }),
  save: (name, description, code, tags = '') => _isObj(name) ? rpc('save_skill', name) : rpc('save_skill', { name, description, code, tags })
};

const docs = {
  lookup: (query, modId = null, includeWeb = false) => _isObj(query) ? rpc('lookup_doc', query) : rpc('lookup_doc', { query, ...(modId ? { modId } : {}), includeWeb })
};

const tools = {
  world,
  player,
  drone,
  skills,
  docs,
  ...world,
  ...player,
  ...drone,
  searchSkills: skills.search,
  listSkills: skills.list,
  saveSkill: skills.save,
  lookupDoc: docs.lookup,
  docsLookup: docs.lookup,
  machineCraft: drone.machineCraft,
  anvilCraft: drone.anvilCraft,
  getMachineRecipes: drone.getMachineRecipes,
  getMachineRecipe: drone.getMachineRecipe,
  scanMachines: drone.scanMachines,
  getMachines: drone.getMachines,
  machineIngredients: drone.machineIngredients,
  getMachineIngredients: drone.getMachineIngredients,
  driveAnvil: drone.driveAnvil,
  anvilDrive: drone.anvilDrive
};
globalThis.world = world;
globalThis.player = player;
globalThis.drone = drone;
globalThis.skills = skills;
globalThis.docs = docs;
// ESM top-level `const docs = ...` in a user script shadows the global and crashes the harness
// (ReferenceError: Cannot access 'docs' before initialization). `kb` is a collision-free alias.
globalThis.kb = docs;
globalThis._worldApi = world;
globalThis._playerApi = player;
globalThis._droneApi = drone;
globalThis.tools = tools;
