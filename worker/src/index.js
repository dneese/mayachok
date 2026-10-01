// gps-tracker Worker: прийом координат + API для мапи.
// Група визначається кодом; gid = SHA-256(code)[0:16]. Сам код ніде не зберігається.

const MAX_HISTORY_HOURS = 12; // скільки історії показує мапа
const STALE_MINUTES = 30; // після цього маркер ховається
const POINT_RETENTION_HOURS = 48; // стільки тримаємо в базі
const USER_RETENTION_DAYS = 30; // неактивні користувачі видаляються
const MAX_NAME_LEN = 24;
const MAX_TRACK_POINTS = 500; // спрощення сліду на сервері

// Алфавіт без i, l, o, u — щоб не плутати при диктуванні телефоном.
// 12 символів × 5 біт = 60 біт: перебір неможливий, а набирається легко.
const CODE_ALPHABET = '0123456789abcdefghjkmnpqrstvwxyz';
const CODE_LENGTH = 12;

function newCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(CODE_LENGTH));
  let out = '';
  for (const b of bytes) out += CODE_ALPHABET[b % CODE_ALPHABET.length];
  // Групуємо по 4: k7qm-2x9d-rt4b — так легше читати вголос і вводити.
  return out.match(/.{1,4}/g).join('-');
}

/** Приводить код до канонічного вигляду: без дефісів, у нижньому регістрі. */
function normalizeCode(code) {
  if (typeof code !== 'string') return '';
  return code.trim().toLowerCase().replace(/[^a-z0-9]/g, '');
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
      'Cache-Control': 'no-store',
    },
  });
}

function bad(message) {
  return json({ error: message }, 400);
}

async function groupIdFor(code) {
  const normalized = normalizeCode(code);
  if (normalized.length < 8 || normalized.length > 64) return null;
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(normalized));
  const hex = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
  return hex.slice(0, 16);
}

function isFiniteNumber(value, min, max) {
  const n = Number(value);
  return Number.isFinite(n) && n >= min && n <= max;
}

async function readParams(request) {
  const url = new URL(request.url);
  const params = new URLSearchParams(url.search);
  if (request.method === 'POST') {
    const type = request.headers.get('Content-Type') || '';
    if (type.includes('application/json')) {
      try {
        const body = await request.json();
        for (const [key, value] of Object.entries(body)) params.set(key, value);
      } catch {
        // не JSON — ігноруємо, працюємо з query
      }
    } else if (type.includes('application/x-www-form-urlencoded')) {
      const form = await request.formData();
      for (const [key, value] of form.entries()) params.set(key, value);
    }
  }
  return params;
}

// Створює або оновлює користувача, повертає uid.
async function upsertUser(db, gid, uid, name) {
  const now = Date.now();
  if (uid) {
    const res = await db
      .prepare(
        `INSERT INTO users (uid, gid, name, last_seen, created_at)
         VALUES (?1, ?2, ?3, ?4, ?4)
         ON CONFLICT(uid) DO UPDATE SET last_seen = ?4`,
      )
      .bind(uid, gid, sanitizeName(name) || null, now)
      .run();
    if (res.success) return uid;
  }
  const found = await db.prepare('SELECT uid FROM users WHERE gid = ?1 ORDER BY created_at LIMIT 1').bind(gid).first();
  if (found && found.uid) return found.uid;
  return crypto.randomUUID();
}

function sanitizeName(name) {
  if (typeof name !== 'string') return '';
  return name.replace(/[\p{C}]/gu, '').trim().slice(0, MAX_NAME_LEN);
}

async function handleCreate(db) {
  // Група не має рядка в базі — вона існує, доки в неї є точки.
  const code = newCode();
  await db.prepare('INSERT INTO groups (gid, created_at) VALUES (?1, ?2) ON CONFLICT(gid) DO NOTHING')
    .bind(await groupIdFor(code), Date.now())
    .run();
  return json({ code, gid: await groupIdFor(code) });
}

async function handleIngest(request, db) {
  const params = await readParams(request);
  const code = params.get('code');
  const gid = await groupIdFor(code);
  if (!gid) return bad('invalid code');

  const lat = params.get('lat');
  const lon = params.get('lon');
  if (!isFiniteNumber(lat, -90, 90)) return bad('lat out of range');
  if (!isFiniteNumber(lon, -180, 180)) return bad('lon out of range');

  let ts = Number(params.get('t'));
  if (!Number.isFinite(ts) || ts <= 0) ts = Date.now();
  else if (ts < 1e11) ts *= 1000; // секунди → мілісекунди
  // Не довіряємо годиннику телефону надто сильно: 5 хв у майбутньому — це баг.
  ts = Math.min(ts, Date.now() + 5 * 60 * 1000);

  const acc = isFiniteNumber(params.get('acc'), 0, 100000) ? Number(params.get('acc')) : null;
  const bat = isFiniteNumber(params.get('bat'), 0, 100) ? Math.round(Number(params.get('bat'))) : null;

  const uid = await upsertUser(db, gid, params.get('uid'), params.get('name'));
  const user = await db.prepare('SELECT name FROM users WHERE uid = ?1').bind(uid).first();

  await db
    .prepare('INSERT INTO points (uid, gid, lat, lon, acc, bat, ts) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)')
    .bind(uid, gid, Number(lat), Number(lon), acc, bat, ts)
    .run();

  return json({ ok: true, uid, name: user ? user.name : null, ts });
}

async function handleGroup(request, db) {
  const params = await readParams(request);
  const gid = await groupIdFor(params.get('code'));
  if (!gid) return bad('invalid code');

  const staleCutoff = Date.now() - STALE_MINUTES * 60 * 1000;
  const { results } = await db
    .prepare(
      `SELECT u.uid, u.name, u.last_seen,
              (SELECT p.lat   FROM points p WHERE p.uid = u.uid ORDER BY p.ts DESC LIMIT 1) AS lat,
              (SELECT p.lon   FROM points p WHERE p.uid = u.uid ORDER BY p.ts DESC LIMIT 1) AS lon,
              (SELECT p.acc   FROM points p WHERE p.uid = u.uid ORDER BY p.ts DESC LIMIT 1) AS acc,
              (SELECT p.bat   FROM points p WHERE p.uid = u.uid ORDER BY p.ts DESC LIMIT 1) AS bat,
              (SELECT p.ts    FROM points p WHERE p.uid = u.uid ORDER BY p.ts DESC LIMIT 1) AS ts
       FROM users u
       WHERE u.gid = ?1 AND u.last_seen >= ?2
       ORDER BY u.created_at`,
    )
    .bind(gid, staleCutoff)
    .all();

  return json({
    now: Date.now(),
    staleAfterMs: STALE_MINUTES * 60 * 1000,
    historyHours: MAX_HISTORY_HOURS,
    users: results.filter((u) => u.lat !== null && u.lon !== null),
  });
}

async function handleTrack(request, db) {
  const params = await readParams(request);
  const gid = await groupIdFor(params.get('code'));
  if (!gid) return bad('invalid code');
  const uid = params.get('uid');
  if (!uid) return bad('uid required');

  let hours = Number(params.get('hours')) || MAX_HISTORY_HOURS;
  hours = Math.min(Math.max(hours, 1), MAX_HISTORY_HOURS);

  const since = Date.now() - hours * 60 * 60 * 1000;
  const { results } = await db
    .prepare(
      `SELECT lat, lon, acc, bat, ts FROM points
       WHERE uid = ?1 AND gid = ?2 AND ts >= ?3
       ORDER BY ts`,
    )
    .bind(uid, gid, since)
    .all();

  const coords = simplify(results, MAX_TRACK_POINTS).map((p) => [p.lon, p.lat]);
  const props = results.length ? { count: results.length, from: results[0].ts, to: results[results.length - 1].ts } : {};

  return json({
    type: 'Feature',
    properties: props,
    geometry: { type: 'LineString', coordinates: coords },
  });
}

// Рівномірне прореджування: лишаємо першу й останню точку, решта — рівномірно.
function simplify(points, limit) {
  if (points.length <= limit) return points;
  const out = [];
  const step = (points.length - 1) / (limit - 1);
  for (let i = 0; i < limit; i++) out.push(points[Math.round(i * step)]);
  return out;
}

async function handleRename(request, db) {
  const params = await readParams(request);
  const gid = await groupIdFor(params.get('code'));
  if (!gid) return bad('invalid code');
  const uid = params.get('uid');
  if (!uid) return bad('uid required');

  const name = sanitizeName(params.get('name'));
  if (!name) return bad('name required');

  await db.prepare('UPDATE users SET name = ?1 WHERE uid = ?2 AND gid = ?3').bind(name, uid, gid).run();

  // Перевіряємо через SELECT, а не за лічильником змін — він нестабільний між версіями D1.
  const found = await db.prepare('SELECT name FROM users WHERE uid = ?1 AND gid = ?2').bind(uid, gid).first();
  if (!found) return json({ error: 'not found' }, 404);
  return json({ ok: true, uid, name: found.name });
}

async function handleHealth(db) {
  const { results } = await db.prepare('SELECT COUNT(*) AS n FROM points').all();
  return json({ ok: true, points: results.length, time: Date.now() });
}

async function cleanup(db) {
  const pointsCutoff = Date.now() - POINT_RETENTION_HOURS * 60 * 60 * 1000;
  const usersCutoff = Date.now() - USER_RETENTION_DAYS * 24 * 60 * 60 * 1000;
  const a = await db.prepare('DELETE FROM points WHERE ts < ?1').bind(pointsCutoff).run();
  const b = await db.prepare('DELETE FROM users WHERE last_seen < ?1').bind(usersCutoff).run();
  const c = await db.prepare('DELETE FROM groups WHERE created_at < ?1').bind(usersCutoff).run();
  return {
    deletedPoints: (a.meta && a.meta.changes) || 0,
    deletedUsers: (b.meta && b.meta.changes) || 0,
    deletedGroups: (c.meta && c.meta.changes) || 0,
  };
}

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') {
      // 204 не допускає тіла — повертаємо лише заголовки.
      return new Response(null, {
        status: 204,
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
          'Access-Control-Allow-Headers': 'Content-Type',
          'Access-Control-Max-Age': '86400',
        },
      });
    }

    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';
    const db = env.DB;

    if (!db) return json({ error: 'DB binding missing' }, 500);

    try {
      if (path === '/api/create' && request.method === 'POST') return await handleCreate(db);
      if (path === '/api/ingest') return await handleIngest(request, db);
      if (path === '/api/group') return await handleGroup(request, db);
      if (path === '/api/track') return await handleTrack(request, db);
      if (path === '/api/rename') return await handleRename(request, db);
      if (path === '/api/health') return await handleHealth(db);

      // Сумісність зі старим gps.php: ?lat=&lon=&t=
      if (url.searchParams.has('lat') && url.searchParams.has('lon')) return await handleIngest(request, db);
      if (url.searchParams.has('tracker')) return json({ ok: true });

      return json(
        {
          ok: false,
          endpoints: ['/api/create', '/api/ingest', '/api/group', '/api/track', '/api/rename', '/api/health'],
        },
        404,
      );
    } catch (error) {
      return json({ error: 'internal', detail: String(error && error.message) }, 500);
    }
  },

  async scheduled(event, env) {
    return cleanup(env.DB);
  },
};
