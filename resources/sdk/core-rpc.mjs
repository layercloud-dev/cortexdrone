const logs = [];
console.log = (...a) => logs.push(a.map(x => typeof x === 'object' ? JSON.stringify(x) : String(x)).join(' '));
globalThis.log = console.log;
globalThis.print = console.log;
globalThis.sleep = (ms) => new Promise(r => setTimeout(r, Math.min(30000, Number(ms) || 0)));
globalThis.wait = globalThis.sleep;

const _killTimer = setTimeout(() => {
  process.stdout.write(JSON.stringify({ ok: true, result: { timedOutAfterMs: 175000 }, logs }));
  process.exit(0);
}, 175000);
_killTimer.unref();

const _mineBatch = [];
const _buildBatch = [];
let _flushedMineTotal = 0;
let _flushedBuildTotal = 0;
let _gatheredItems = false;
let _handledItems = false;

// Cap what an LLM sees: huge tool payloads (full chest contents, thousand-block scans) blow up the
// prompt window, cause the model to spiral into essay mode and starve the 20 TPS server tick.
// Recursively keep every field, but truncate arrays at 24 entries (with counts) and long strings at 1200 chars.
// Callers can bypass with opts.raw=true (world-api does local fn filtering before the LLM sees the payload).
// NOTE: _capSize only runs when JSON length > 24000 (default rpc path). For payloads between ~4KB
// (typical kb.lookup / getRecipes result) and the 24KB threshold the FULL payload reaches the LLM
// untruncated — this was the prompt-window blowup behind repeated essay-mode drift. So the result
// is ALWAYS capped here; an explicit large cap (see rpc) preserves the old behaviour for raw callers.
function _capSize(value, depth = 0) {
  if (value == null) return value;
  if (typeof value === 'string') return value.length > 1200 ? value.slice(0, 1200) + `…(+${value.length - 1200} chars)` : value;
  if (Array.isArray(value)) {
    if (value.length <= 24) return value.map(v => _capSize(v, depth + 1));
    return [...value.slice(0, 24).map(v => _capSize(v, depth + 1)), { __truncated: value.length - 24 }];
  }
  if (typeof value === 'object') {
    if (depth > 5) return '[…]';
    const out = {};
    for (const k of Object.keys(value)) {
      if (k === 'summary') { out[k] = value[k]; continue; } // summaries are already compact strings
      out[k] = _capSize(value[k], depth + 1);
    }
    return out;
  }
  return value;
}

function _jsonLen(v) { try { return JSON.stringify(v).length; } catch (e) { return Infinity; } }

async function rpc(method, args = {}, opts = {}) {
  let r;
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      r = await fetch(`http://127.0.0.1:${HTTP_PORT}/rpc`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ droneId: DRONE_ID, player: PLAYER_NAME, method, args })
      });
      break;
    } catch (e) {
      if (attempt < 3) { await sleep(250 * attempt); continue; }
      throw e;
    }
  }
  const data = await r.json();
  let out = (data && typeof data === 'object') ? data : { ok: false, error: 'Empty RPC response' };
  if (!opts || !opts.raw) {
    // Always cap small recipe/chest payloads too; the old >24000 gate let 4-20KB
    // kb.lookup/getRecipes dumps through untruncated. Keep a high escape hatch for
    // callers that explicitly need the full body via opts.raw=true.
    const cap = (opts && typeof opts.cap === 'number') ? opts.cap : 8000;
    if (_jsonLen(out) > cap) {
      out = _capSize(out);
      out.truncated = true;
    }
  }
  return out;
}

// Batched RPC: queue multiple independent inspections into one HTTP round-trip.
// Dispatches in parallel via Promise.all instead of serial awaits so a turn that
// needs kb.lookup + getRecipes + findItemInChests costs ~1 round-trip of latency.
async function rpcBatch(calls) {
  if (!Array.isArray(calls) || calls.length <= 1) {
    return calls && calls[0] ? await rpc(calls[0][0], calls[0][1] || {}) : {};
  }
  return Promise.all(calls.map(([m, a]) => {
    try { return rpc(m, a || {}); } catch (e) { return { ok: false, error: String(e) }; }
  }));
}
globalThis.rpcBatch = rpcBatch; globalThis.rpc = rpc;

const _ACTIVE_TASK_MODES = new Set(['MINE', 'BUILD', 'SCOOP', 'SMELT', 'MACHINE', 'CARRY', 'GOTO', 'HUNT', 'DROP', 'DEPOSIT']);

// Adaptive poll backoff: starts 400ms and grows to a 2000ms cap.
// A slow task polling get_drone every 200ms floods the 8s main-thread latch with
// cheap but contending RPCs. A task that is actually idle exits as soon as the
// predicate flips; the 2s cap keeps steady-state polling to ~0.5 RPC/s/drone.
async function _waitTask(predicateFn, maxWaitMs = 150000) {
  const t0 = Date.now();
  let pollMs = 400;
  while (Date.now() - t0 < maxWaitMs) {
    const st = await rpc('get_drone', { loopId: typeof LOOP_ID === 'number' ? LOOP_ID : 0 });
    if (st && st.aborted) {
      throw new Error('SUPERSEDED');
    }
    if (!st || st.ok === false || predicateFn(st)) {
      return st || {};
    }
    await new Promise(r => setTimeout(r, pollMs));
    pollMs = Math.min(2000, Math.round(pollMs * pollMs / 400));
  }
  return await rpc('get_drone', { loopId: typeof LOOP_ID === 'number' ? LOOP_ID : 0 });
}

async function _waitIdle(maxWaitMs = 60000) {
  return await _waitTask(
    (st) =>
      (st.mineQueueSize || 0) === 0 &&
      (st.buildQueueSize || 0) === 0 &&
      (st.scoopQueueSize || 0) === 0 &&
      !_ACTIVE_TASK_MODES.has(st.mode),
    maxWaitMs
  );
}

// --- scanBlocks auto-poll for large time-sliced scans (radius reset above threshold) ---
// RpcDispatcher queues large scans (radius > 32) and returns a pending/queued stub
// with jobId instead of the final shape. This helper polls poll_scan until done or
// the timeout, then normalizes every path to {count, blocks, scannedVoxels, truncated,
// radius, done, jobId} so sync and async scanBlocks are byte-equivalent.

const _SCAN_POLL_TIMEOUT_MS = 30000;
const _SCAN_POLL_INITIAL_MS = 250;
const _SCAN_POLL_MAX_MS = 2000;

function _isPendingScan(r) {
  return !!(r && (r.queued || r.pending || r.busy) && !r.done && r.jobId);
}

function _normalizeScanResult(r, fallbackRadius) {
  if (!r || typeof r !== 'object') {
    return { count: 0, blocks: [], scannedVoxels: 0, truncated: true, radius: fallbackRadius || 0, done: true, jobId: null };
  }
  const blocks = Array.isArray(r.blocks) ? r.blocks : [];
  return {
    ok: r.ok !== false,
    count: typeof r.count === 'number' ? r.count : blocks.length,
    blocks,
    scannedVoxels: typeof r.scannedVoxels === 'number' ? r.scannedVoxels : (r.scannedVoxels ?? 0),
    truncated: !!r.truncated,
    radius: typeof r.radius === 'number' ? r.radius : (typeof r.cappedRadius === 'number' ? r.cappedRadius : (fallbackRadius || 0)),
    done: r.done !== false,
    jobId: r.jobId || null,
    ...(r.error ? { error: r.error } : {})
  };
}

async function _awaitScanJob(jobId, fallbackRadius, timeoutMs = _SCAN_POLL_TIMEOUT_MS) {
  const startedAt = Date.now();
  let pollMs = _SCAN_POLL_INITIAL_MS;
  let last = { jobId, done: false, blocks: [], count: 0, truncated: true, scannedVoxels: 0, radius: fallbackRadius || 0 };
  let consecutiveErrors = 0;
  while (Date.now() - startedAt < timeoutMs) {
    let r;
    try {
      r = await rpc('poll_scan', { jobId }, { raw: true });
    } catch (e) {
      consecutiveErrors++;
      if (consecutiveErrors >= 3) break;
      await new Promise(res => setTimeout(res, pollMs));
      pollMs = Math.min(_SCAN_POLL_MAX_MS, Math.round(pollMs * 1.5 + pollMs * pollMs / 400));
      continue;
    }
    if (r && r.done) {
      if (Array.isArray(r.blocks) && r.blocks.length === 0) {
        r.truncated = r.truncated ?? true;
      }
      return _normalizeScanResult(r, fallbackRadius);
    }
    if (r) { last = r; consecutiveErrors = 0; } else consecutiveErrors++;
    if (r && r.done === false && r.error && !r.pending && !r.blocks) break;
    if (consecutiveErrors >= 3) break;
    await new Promise(res => setTimeout(res, pollMs));
    pollMs = Math.min(_SCAN_POLL_MAX_MS, Math.round(pollMs * 1.5 + pollMs * pollMs / 400));
    if (r && r.aborted) throw new Error('SUPERSEDED');
  }
  // Timeout or poll error: return whatever the server last reported, normalized.
  return _normalizeScanResult(last, fallbackRadius);
}

async function _flushBatches() {
  if (_mineBatch.length > 0) {
    const blocks = _mineBatch.splice(0, _mineBatch.length);
    const r = await rpc('queue_mine', { blocks, exactOnly: true, deliver: false });
    const q = (r && typeof r.queued === 'number') ? r.queued : blocks.length;
    _flushedMineTotal += q;
    if (q > 0) {
      _gatheredItems = true;
      await _waitIdle();
    }
  }
  if (_buildBatch.length > 0) {
    const tasks = _buildBatch.splice(0, _buildBatch.length);
    const r = await rpc('queue_build', { tasks });
    const q = (r && typeof r.queued === 'number') ? r.queued : tasks.length;
    _flushedBuildTotal += q;
    if (q > 0) {
      _handledItems = true;
      await _waitIdle();
    }
  }
}

async function _finalizeScript() {
  await _flushBatches();
  if (_gatheredItems || _flushedMineTotal > 0 || _flushedBuildTotal > 0) {
    await _waitIdle(60000);
  }
  const finalTurn = typeof IS_FINAL_TURN === 'boolean' ? IS_FINAL_TURN : true;
  if (finalTurn && _gatheredItems && !_handledItems) {
    const st = await rpc('get_drone', { loopId: typeof LOOP_ID === 'number' ? LOOP_ID : 0 });
    if (st && !st.aborted && (st.totalItems || 0) > 0 && st.mode === 'FOLLOW') {
      const dr = await rpc('deliver_to_player', { filter: 'all', count: 512 });
      if (dr && dr.flyingToPlayer) {
        await _waitIdle(60000);
      }
    }
  }
}

function hsvToRgb(hDeg, s = 1, v = 1) {
  const h = ((Number(hDeg) % 360) + 360) % 360;
  const c = v * s;
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
  const m = v - c;
  let r1 = 0, g1 = 0, b1 = 0;
  if (h < 60) { r1 = c; g1 = x; }
  else if (h < 120) { r1 = x; g1 = c; }
  else if (h < 180) { g1 = c; b1 = x; }
  else if (h < 240) { g1 = x; b1 = c; }
  else if (h < 300) { r1 = x; b1 = c; }
  else { r1 = c; b1 = x; }
  const r = Math.min(255, Math.max(0, Math.round((r1 + m) * 255)));
  const g = Math.min(255, Math.max(0, Math.round((g1 + m) * 255)));
  const b = Math.min(255, Math.max(0, Math.round((b1 + m) * 255)));
  return (r << 16) | (g << 8) | b;
}
globalThis.hsvToRgb = hsvToRgb;

const _isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
