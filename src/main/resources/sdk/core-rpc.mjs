const logs = [];
console.log = (...a) => logs.push(a.map(x => typeof x === 'object' ? JSON.stringify(x) : String(x)).join(' '));
globalThis.log = console.log;
globalThis.sleep = (ms) => new Promise(r => setTimeout(r, Math.min(30000, Number(ms) || 0)));

const _killTimer = setTimeout(() => {
  process.stdout.write(JSON.stringify({ ok: true, result: { timedOutAfterMs: 175000 }, logs }));
  process.exit(0);
}, 175000);
_killTimer.unref();

// Cap what an LLM sees: huge inspection payloads blow up the prompt window.
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
      if (k === 'summary') { out[k] = value[k]; continue; }
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
        body: JSON.stringify({ droneId: DRONE_ID, loopId: typeof LOOP_ID === 'number' ? LOOP_ID : 0, player: PLAYER_NAME, method, args })
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
    const cap = (opts && typeof opts.cap === 'number') ? opts.cap : 8000;
    if (_jsonLen(out) > cap) {
      out = _capSize(out);
      out.truncated = true;
    }
  }
  return out;
}

// Low-level RPC stays private to the SDK. Code Mode only gets the `drone`
// master-tool surface, so arbitrary legacy RPC names cannot be called directly.

async function _state() {
  return await rpc('state', { loopId: typeof LOOP_ID === 'number' ? LOOP_ID : 0 });
}

// Wait for the one active action/session to satisfy a predicate. There is no
// task queue: a script is blocked on the physical action it just started.
async function _waitUntil(predicateFn, maxWaitMs = 150000) {
  const t0 = Date.now();
  let pollMs = 250;
  while (Date.now() - t0 < maxWaitMs) {
    const st = await _state();
    if (st && st.aborted) throw new Error('SUPERSEDED');
    if (!st || st.ok === false || predicateFn(st)) return st || {};
    await new Promise(r => setTimeout(r, pollMs));
    pollMs = Math.min(1500, Math.round(pollMs * 1.5 + pollMs * pollMs / 800));
  }
  return await _state();
}

async function _waitIdle(maxWaitMs = 60000) {
  return await _waitUntil(
    (st) => st.busy === false,
    maxWaitMs
  );
}

// --- scanBlocks auto-poll for large time-sliced inspections ---
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
  return _normalizeScanResult(last, fallbackRadius);
}

// All master primitives await their own physical completion. Kept as a stable
// hook for the runtime wrapper; intentionally does not drain an instruction queue.
async function _finalizeScript() {

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
