const MEMBER_RETENTION_DAYS = 30;
const GROUP_RETENTION_DAYS = 30;
const LAST_SEEN_THROTTLE_MS = 5 * 60 * 1000;
const FRESH_MINUTES = 30;
const MAX_NAME_LEN = 24;
const MAX_UID_LEN = 128;
const MAX_CODE_LEN = 64;
const MAX_BODY_BYTES = 64 * 1024;
const MAX_DIAG_EVENTS = 200;
const MAX_DIAG_TEXT_LEN = 200;
const MAX_IMPLAUSIBLE_SPEED_MS = 1000;
const DIAG_RETENTION_MS = 48 * 60 * 60 * 1000;
const TRAIL_RETENTION_MS = 65 * 60 * 1000;
const TRAIL_MAX_MINUTES = 70;
const TRAIL_DEFAULT_MINUTES = 60;
const TRAIL_MAX_POINTS = 5000;
const WRITE_MIN_GAP_MS = 8000;
const CODE_ALPHABET = '0123456789abcdefghjkmnpqrstvwxyz';
const CODE_LENGTH = 12;
const TOKEN_BYTES = 32;
const TOKEN_HEADER = 'x-member-token';
const ADMIN_HEADER = 'x-admin-secret';

const trackCache = new Map();
const groupCache = new Map();
const lastGood = new Map();
const GROUP_CACHE_MS = 5000;
const meters = { writes: 0, throttled: 0, rejected: 0, cacheHits: 0, dbErrors: 0 };

function newCode() {
  const limit = 256 - (256 % CODE_ALPHABET.length);
  let out = '';
  while (out.length < CODE_LENGTH) {
    const bytes = crypto.getRandomValues(new Uint8Array(CODE_LENGTH));
    for (const b of bytes) {
      if (b >= limit) continue;
      out += CODE_ALPHABET[b % CODE_ALPHABET.length];
      if (out.length === CODE_LENGTH) break;
    }
  }
  return out.match(/.{1,4}/g).join('-');
}

function newToken() {
  const bytes = crypto.getRandomValues(new Uint8Array(TOKEN_BYTES));
  return [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
}

function normalizeCode(code) {
  if (typeof code !== 'string') return '';
  return code.trim().toLowerCase().replace(/[^a-z0-9]/g, '');
}

function sanitizeName(name) {
  if (typeof name !== 'string') return '';
  return name.replace(/[\p{C}]/gu, '').trim().slice(0, MAX_NAME_LEN);
}

function validUid(uid) {
  return typeof uid === 'string' && uid.length > 0 && uid.length <= MAX_UID_LEN && /^[\w.:-]+$/.test(uid);
}

function isFiniteNumber(value, min, max) {
  const n = Number(value);
  return Number.isFinite(n) && n >= min && n <= max;
}

async function sha256(value) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

async function groupIdFor(code) {
  const normalized = normalizeCode(code);
  if (normalized.length < 8 || normalized.length > MAX_CODE_LEN) return null;
  return (await sha256(normalized)).slice(0, 16);
}

function json(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': `Content-Type, ${TOKEN_HEADER}, ${ADMIN_HEADER}`,
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
      ...extraHeaders,
    },
  });
}

function bad(message) {
  return json({ error: message }, 400);
}

function unauthorized() {
  return json({ error: 'unauthorized' }, 401);
}

function publicError() {
  return json({ error: 'internal error' }, 500);
}

async function readParams(request) {
  const url = new URL(request.url);
  const params = new URLSearchParams(url.search);
  if (request.method !== 'POST') return params;
  const type = request.headers.get('Content-Type') || '';
  const declaredLength = Number(request.headers.get('Content-Length') || 0);
  if (declaredLength > MAX_BODY_BYTES) throw new Error('request too large');
  if (type.includes('application/json')) {
    const raw = await request.text();
    if (raw.length > MAX_BODY_BYTES) throw new Error('request too large');
    let body;
    try {
      body = JSON.parse(raw);
    } catch {
      throw new Error('invalid json');
    }
    if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error('invalid json');
    for (const [key, value] of Object.entries(body)) {
      if (typeof value !== 'string' && typeof value !== 'number' && typeof value !== 'boolean') continue;
      params.set(key, String(value));
    }
  } else if (type.includes('application/x-www-form-urlencoded')) {
    const raw = await request.text();
    if (raw.length > MAX_BODY_BYTES) throw new Error('request too large');
    params.set(new URLSearchParams(raw));
  }
  return params;
}

function tokenFrom(request) {
  return request.headers.get(TOKEN_HEADER) || '';
}

async function memberTokenMatches(db, gid, uid, token) {
  if (!token) return false;
  const row = await db.prepare('SELECT token_hash FROM users WHERE gid = ?1 AND uid = ?2').bind(gid, uid).first();
  if (!row || !row.token_hash || row.token_hash === 'legacy-disabled') return false;
  return row.token_hash === await sha256(token);
}

async function requireMember(request, db, params, gid) {
  const uid = params.get('uid');
  if (!validUid(uid)) return null;
  if (!await memberTokenMatches(db, gid, uid, tokenFrom(request))) return null;
  return uid;
}

function distanceMeters(lat1, lon1, lat2, lon2) {
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a = Math.sin(dLat / 2) ** 2 + Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLon / 2) ** 2;
  return 2 * 6371000 * Math.asin(Math.min(1, Math.sqrt(a)));
}

async function touchGroup(db, gid, now) {
  await db.prepare('INSERT INTO groups (gid, created_at, last_activity) VALUES (?1, ?2, ?2) ON CONFLICT(gid) DO UPDATE SET last_activity = excluded.last_activity')
    .bind(gid, now).run();
}

async function handleCreate(request, db) {
  const params = await readParams(request);
  const uid = params.get('uid');
  const name = sanitizeName(params.get('name'));
  const hasMember = Boolean(uid || name);
  if (hasMember && !validUid(uid)) return bad('uid required');
  if (hasMember && !name) return bad('name required');
  const code = newCode();
  const gid = await groupIdFor(code);
  const now = Date.now();
  if (!hasMember) {
    await db.prepare('INSERT INTO groups (gid, created_at, last_activity) VALUES (?1, ?2, ?2)').bind(gid, now).run();
    return json({ ok: true, code, gid });
  }
  const token = newToken();
  const tokenHash = await sha256(token);
  await db.batch([
    db.prepare('INSERT INTO groups (gid, created_at, last_activity) VALUES (?1, ?2, ?2)').bind(gid, now),
    db.prepare('INSERT INTO users (gid, uid, token_hash, name, last_seen, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?5)')
      .bind(gid, uid, tokenHash, name, now),
  ]);
  return json({ ok: true, code, gid, uid, token });
}

async function handleJoin(request, db) {
  const params = await readParams(request);
  const gid = await groupIdFor(params.get('code'));
  if (!gid) return bad('invalid code');
  const uid = params.get('uid');
  if (!validUid(uid)) return bad('uid required');
  const name = sanitizeName(params.get('name'));
  if (!name) return bad('name required');
  const existing = await db.prepare('SELECT token_hash FROM users WHERE gid = ?1 AND uid = ?2').bind(gid, uid).first();
  if (existing) {
    const suppliedToken = tokenFrom(request);
    if (existing.token_hash === 'legacy-disabled' && !suppliedToken) {
      const token = newToken();
      await db.prepare('UPDATE users SET token_hash = ?1, name = ?2, last_seen = ?3 WHERE gid = ?4 AND uid = ?5')
        .bind(await sha256(token), name, Date.now(), gid, uid).run();
      await touchGroup(db, gid, Date.now());
      groupCache.delete(gid);
      return json({ ok: true, uid, name, gid, token });
    }
    if (!await memberTokenMatches(db, gid, uid, suppliedToken)) return unauthorized();
    await db.prepare('UPDATE users SET name = ?1, last_seen = ?2 WHERE gid = ?3 AND uid = ?4').bind(name, Date.now(), gid, uid).run();
    await touchGroup(db, gid, Date.now());
    groupCache.delete(gid);
    return json({ ok: true, uid, name, gid });
  }
  const token = newToken();
  const tokenHash = await sha256(token);
  const now = Date.now();
  await db.batch([
    db.prepare('INSERT INTO users (gid, uid, token_hash, name, last_seen, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?5)')
      .bind(gid, uid, tokenHash, name, now),
    db.prepare('INSERT INTO groups (gid, created_at, last_activity) VALUES (?1, ?2, ?2) ON CONFLICT(gid) DO UPDATE SET last_activity = excluded.last_activity')
      .bind(gid, now),
  ]);
  groupCache.delete(gid);
  return json({ ok: true, uid, name, gid, token });
}

async function claimWriteSlot(db, gid, uid, now) {
  const cutoff = now - WRITE_MIN_GAP_MS;
  const row = await db.prepare(
    `INSERT INTO rate_limits (gid, uid, last_write) VALUES (?1, ?2, ?3)
     ON CONFLICT(gid, uid) DO UPDATE SET last_write = excluded.last_write
     WHERE rate_limits.last_write <= ?4
     RETURNING last_write`,
  ).bind(gid, uid, now, cutoff).first();
  return !!row;
}

async function handleIngest(request, db) {
  const params = await readParams(request);
  const gid = await groupIdFor(params.get('code'));
  if (!gid) return bad('invalid code');
  const uid = params.get('uid');
  if (!validUid(uid)) return bad('uid required');
  if (!await memberTokenMatches(db, gid, uid, tokenFrom(request, params))) return unauthorized();
  const lat = params.get('lat');
  const lon = params.get('lon');
  if (!isFiniteNumber(lat, -90, 90)) return bad('lat out of range');
  if (!isFiniteNumber(lon, -180, 180)) return bad('lon out of range');
  let ts = Number(params.get('t'));
  if (!Number.isFinite(ts) || ts <= 0) ts = Date.now();
  else if (ts < 1e11) ts *= 1000;
  if (ts > Date.now() + 60 * 1000) ts = Date.now();
  const acc = isFiniteNumber(params.get('acc'), 0, 100000) ? Number(params.get('acc')) : null;
  const bat = isFiniteNumber(params.get('bat'), 0, 100) ? Math.round(Number(params.get('bat'))) : null;
  const now = Date.now();
  if (!await claimWriteSlot(db, gid, uid, now)) {
    meters.throttled++;
    return json({ error: 'rate limited', retryAfter: Math.ceil(WRITE_MIN_GAP_MS / 1000) }, 429, { 'Retry-After': String(Math.ceil(WRITE_MIN_GAP_MS / 1000)) });
  }
  const previous = await db.prepare('SELECT lat, lon, point_ts FROM users WHERE gid = ?1 AND uid = ?2').bind(gid, uid).first();
  if (previous && previous.lat != null && previous.point_ts) {
    const seconds = (Date.now() - Number(previous.point_ts)) / 1000;
    if (seconds > 0) {
      const speed = distanceMeters(Number(previous.lat), Number(previous.lon), Number(lat), Number(lon)) / seconds;
      if (speed > MAX_IMPLAUSIBLE_SPEED_MS) {
        meters.rejected++;
        return json({ ok: false, error: 'implausible speed' }, 422);
      }
    }
  }
  const member = await db.prepare('SELECT token_hash FROM users WHERE gid = ?1 AND uid = ?2').bind(gid, uid).first();
  const batch = await db.batch([
    db.prepare(
      `INSERT INTO users (gid, uid, token_hash, name, last_seen, created_at, lat, lon, acc, bat, point_ts)
       VALUES (?1, ?2, ?3, ?4, ?5, ?5, ?6, ?7, ?8, ?9, ?10)
       ON CONFLICT(gid, uid) DO UPDATE SET
         last_seen = ?5, name = COALESCE(?4, users.name), lat = ?6, lon = ?7, acc = ?8, bat = ?9, point_ts = ?10`,
    ).bind(gid, uid, member.token_hash, sanitizeName(params.get('name')) || null, now, Number(lat), Number(lon), acc, bat, ts),
    db.prepare('INSERT INTO points (gid, uid, ts, lat, lon, acc) VALUES (?1, ?2, ?3, ?4, ?5, ?6) ON CONFLICT(gid, uid, ts) DO NOTHING')
      .bind(gid, uid, ts, Number(lat), Number(lon), acc),
    db.prepare('INSERT INTO groups (gid, created_at, last_activity) VALUES (?1, ?2, ?2) ON CONFLICT(gid) DO UPDATE SET last_activity = excluded.last_activity')
      .bind(gid, now),
  ]);
  if (!batch[0] || !batch[0].success) return publicError();
  meters.writes += 2;
  return json({ ok: true, uid, ts });
}

async function handleGroup(db, gid) {
  const hit = cacheGet(gid);
  if (hit) {
    meters.cacheHits++;
    return json(hit);
  }
  const { results } = await db.prepare(
    `SELECT uid, name, last_seen, lat, lon, acc, bat, point_ts AS ts
     FROM users WHERE gid = ?1 ORDER BY created_at`,
  ).bind(gid).all();
  const body = {
    now: Date.now(),
    staleAfterMs: FRESH_MINUTES * 60 * 1000,
    retentionDays: MEMBER_RETENTION_DAYS,
    users: results,
  };
  cachePut(gid, body);
  lastGood.set(gid, body);
  return json(body);
}

function cacheGet(gid) {
  const hit = groupCache.get(gid);
  return hit && Date.now() - hit.at < GROUP_CACHE_MS ? hit.body : null;
}

function cachePut(gid, body) {
  if (groupCache.size > 500) {
    for (const [key, value] of groupCache) {
      if (Date.now() - value.at > GROUP_CACHE_MS) groupCache.delete(key);
      if (groupCache.size <= 400) break;
    }
  }
  groupCache.set(gid, { at: Date.now(), body });
}

async function handleTrack(request, db) {
  const params = await readParams(request);
  const gid = await groupIdFor(params.get('code'));
  if (!gid) return bad('invalid code');
  const minutesRaw = Number(params.get('minutes'));
  const minutes = Number.isFinite(minutesRaw) ? Math.min(Math.max(Math.round(minutesRaw), 1), TRAIL_MAX_MINUTES) : TRAIL_DEFAULT_MINUTES;
  const since = Date.now() - minutes * 60 * 1000;
  const uid = params.get('uid');
  const single = uid && uid !== 'all';
  if (single && !validUid(uid)) return bad('uid required');
  const key = `${gid}:${single ? uid : 'all'}:${minutes}`;
  const hit = trackGet(key);
  if (hit) {
    meters.cacheHits++;
    return json(hit);
  }
  const sql = 'SELECT uid, ts, lat, lon, acc FROM points WHERE gid = ?1 AND ts >= ?2' + (single ? ' AND uid = ?3' : '') + ' ORDER BY ts ASC LIMIT ?' + (single ? '4' : '3');
  const stmt = db.prepare(sql);
  const { results } = single ? await stmt.bind(gid, since, uid, TRAIL_MAX_POINTS).all() : await stmt.bind(gid, since, TRAIL_MAX_POINTS).all();
  const tracks = {};
  for (const row of results) {
    if (!tracks[row.uid]) tracks[row.uid] = [];
    tracks[row.uid].push({ ts: row.ts, lat: row.lat, lon: row.lon, acc: row.acc });
  }
  const body = { ok: true, now: Date.now(), minutes, tracks };
  trackPut(key, body);
  return json(body);
}

function trackGet(key) {
  const hit = trackCache.get(key);
  return hit && Date.now() - hit.at < GROUP_CACHE_MS ? hit.body : null;
}

function trackPut(key, body) {
  if (trackCache.size > 500) {
    for (const [k, value] of trackCache) {
      if (Date.now() - value.at > GROUP_CACHE_MS) trackCache.delete(k);
      if (trackCache.size <= 400) break;
    }
  }
  trackCache.set(key, { at: Date.now(), body });
}

async function handleLeave(request, db) {
  const params = await readParams(request);
  const gid = await groupIdFor(params.get('code'));
  if (!gid) return bad('invalid code');
  const uid = await requireMember(request, db, params, gid);
  if (!uid) return unauthorized();
  const batch = await db.batch([
    db.prepare('DELETE FROM users WHERE gid = ?1 AND uid = ?2').bind(gid, uid),
    db.prepare('DELETE FROM points WHERE gid = ?1 AND uid = ?2').bind(gid, uid),
    db.prepare('DELETE FROM rate_limits WHERE gid = ?1 AND uid = ?2').bind(gid, uid),
  ]);
  groupCache.delete(gid);
  lastGood.delete(gid);
  for (const key of trackCache.keys()) if (key.startsWith(gid + ':')) trackCache.delete(key);
  return json({ ok: true, uid, deletedUser: (batch[0].meta && batch[0].meta.changes) || 0 });
}

async function handleRename(request, db) {
  const params = await readParams(request);
  const gid = await groupIdFor(params.get('code'));
  if (!gid) return bad('invalid code');
  const uid = await requireMember(request, db, params, gid);
  if (!uid) return unauthorized();
  const name = sanitizeName(params.get('name'));
  if (!name) return bad('name required');
  await db.prepare('UPDATE users SET name = ?1, last_seen = ?2 WHERE uid = ?3 AND gid = ?4').bind(name, Date.now(), uid, gid).run();
  await touchGroup(db, gid, Date.now());
  groupCache.delete(gid);
  const found = await db.prepare('SELECT name FROM users WHERE uid = ?1 AND gid = ?2').bind(uid, gid).first();
  if (!found) return json({ error: 'not found' }, 404);
  return json({ ok: true, uid, name: found.name });
}

async function handleHealth() {
  return json({ ok: true });
}

async function handleDiagIngest(request, db) {
  const raw = await request.text();
  if (raw.length > MAX_BODY_BYTES) return json({ error: 'request too large' }, 413);
  let body;
  try {
    body = JSON.parse(raw);
  } catch {
    return bad('invalid json');
  }
  const gid = await groupIdFor(body.code);
  if (!gid) return bad('invalid code');
  const uid = String(body.uid || '');
  if (!validUid(uid)) return bad('uid required');
  const token = request.headers.get(TOKEN_HEADER) || body.token || '';
  if (!await memberTokenMatches(db, gid, uid, token)) return unauthorized();
  const events = Array.isArray(body.events) ? body.events : [];
  const limited = events.slice(0, MAX_DIAG_EVENTS);
  if (limited.length === 0) return json({ ok: true, stored: 0 });
  const session = String(body.session || '').slice(0, 16);
  const dropped = Number(body.dropped) || 0;
  const statements = limited.map((event) => db.prepare(
    'INSERT INTO diag (gid, uid, session, ts, e, a, b, c, s) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)',
  ).bind(gid, uid, session, Number(event.t) || Date.now(), Number(event.e) || 0, Number(event.a) || 0, Number(event.b) || 0, Number(event.c) || 0, String(event.s || '').slice(0, MAX_DIAG_TEXT_LEN)));
  await db.batch(statements);
  meters.writes += statements.length;
  return json({ ok: true, stored: statements.length, dropped });
}

async function handleDiagRead(request, db, env) {
  const configured = request.headers.get(ADMIN_HEADER) || '';
  const secret = env?.ADMIN_SECRET || env?.DEVELOPER_SECRET || '';
  if (!secret || configured !== secret) return unauthorized();
  const params = await readParams(request);
  const gid = await groupIdFor(params.get('code'));
  if (!gid) return bad('invalid code');
  const uid = params.get('uid');
  const limit = Math.min(Math.max(Number(params.get('limit')) || 500, 1), 5000);
  const base = 'SELECT session, ts, e, a, b, c, s FROM diag WHERE gid = ?1';
  const { results } = uid && uid !== 'all'
    ? await db.prepare(base + ' AND uid = ?2 ORDER BY ts ASC LIMIT ?3').bind(gid, uid, limit).all()
    : await db.prepare(base + ' ORDER BY ts ASC LIMIT ?2').bind(gid, limit).all();
  return json({ ok: true, events: results });
}

async function handleCleanup(db) {
  const diagCutoff = Date.now() - DIAG_RETENTION_MS;
  const usersCutoff = Date.now() - MEMBER_RETENTION_DAYS * 24 * 60 * 60 * 1000;
  const groupsCutoff = Date.now() - GROUP_RETENTION_DAYS * 24 * 60 * 60 * 1000;
  const trailCutoff = Date.now() - TRAIL_RETENTION_MS;
  const b = await db.prepare('DELETE FROM users WHERE last_seen < ?1').bind(usersCutoff).run();
  await db.prepare('DELETE FROM points WHERE gid NOT IN (SELECT gid FROM users) OR ts < ?1').bind(trailCutoff).run();
  await db.prepare('DELETE FROM rate_limits WHERE gid NOT IN (SELECT gid FROM users) OR last_write < ?1').bind(usersCutoff).run();
  const c = await db.prepare('DELETE FROM groups WHERE (last_activity < ?1 OR last_activity IS NULL) AND gid NOT IN (SELECT gid FROM users WHERE last_seen >= ?1)').bind(groupsCutoff, usersCutoff).run();
  const e = await db.prepare('DELETE FROM diag WHERE ts < ?1 OR gid NOT IN (SELECT gid FROM users)').bind(diagCutoff).run();
  const t = await db.prepare('DELETE FROM points WHERE ts < ?1').bind(trailCutoff).run();
  return {
    deletedUsers: (b.meta && b.meta.changes) || 0,
    deletedGroups: (c.meta && c.meta.changes) || 0,
    deletedDiag: (e.meta && e.meta.changes) || 0,
    deletedPoints: (t.meta && t.meta.changes) || 0,
  };
}

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') {
      return new Response(null, {
        status: 204,
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
          'Access-Control-Allow-Headers': `Content-Type, ${TOKEN_HEADER}, ${ADMIN_HEADER}`,
          'Access-Control-Max-Age': '86400',
        },
      });
    }
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';
    if (typeof WEB !== 'undefined') {
      const asset = WEB[path === '/' ? '/index.html' : path];
      if (asset) {
        return new Response(asset.body, {
          headers: {
            'content-type': asset.type,
            'cache-control': 'no-cache',
            'x-content-type-options': 'nosniff',
            'referrer-policy': 'no-referrer',
            'x-frame-options': 'DENY',
            'content-security-policy': "default-src 'self'; script-src 'self' https://unpkg.com; style-src 'self' 'unsafe-inline' https://unpkg.com; img-src 'self' data: https://tile.openstreetmap.org; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
            'permissions-policy': 'geolocation=()',
          },
        });
      }
    }
    const db = env.DB;
    if (!db) return publicError();
    try {
      if (path === '/api/create' && request.method === 'POST') return await handleCreate(request, db);
      if (path === '/api/join') return await handleJoin(request, db);
      if (path === '/api/ingest') return await handleIngest(request, db);
      if (path === '/api/group') {
        let gid;
        try {
          const params = await readParams(request);
          gid = await groupIdFor(params.get('code'));
        } catch {
          return bad('invalid request');
        }
        if (!gid) return bad('invalid code');
        try {
          return await handleGroup(db, gid);
        } catch {
          meters.dbErrors++;
          const good = lastGood.get(gid);
          if (good) return json({ ...good, stale: true }, 200, { Warning: '110 mayachok "Response is Stale"' });
          return publicError();
        }
      }
      if (path === '/api/track') return await handleTrack(request, db);
      if (path === '/api/rename') return await handleRename(request, db);
      if (path === '/api/leave') return await handleLeave(request, db);
      if (path === '/api/diag' && request.method === 'POST') return await handleDiagIngest(request, db);
      if (path === '/api/diag') return await handleDiagRead(request, db, env);
      if (path === '/api/health') return await handleHealth();
      if (url.searchParams.has('lat') && url.searchParams.has('lon')) return await handleIngest(request, db);
      if (url.searchParams.has('tracker')) return json({ ok: true });
      return json({ error: 'not found' }, 404);
    } catch (error) {
      if (String(error && error.message).includes('request too large')) return json({ error: 'request too large' }, 413);
      if (String(error && error.message).includes('invalid json')) return bad('invalid json');
      return publicError();
    }
  },
  async scheduled(event, env) {
    return handleCleanup(env.DB);
  },
};
