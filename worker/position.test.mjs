import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import worker from './src/index.js';

const schema = `
PRAGMA foreign_keys=ON;
CREATE TABLE users (gid TEXT NOT NULL, uid TEXT NOT NULL, token_hash TEXT NOT NULL, name TEXT, last_seen INTEGER NOT NULL, created_at INTEGER NOT NULL, lat REAL, lon REAL, acc REAL, bat INTEGER, point_ts INTEGER, PRIMARY KEY(gid,uid));
CREATE TABLE points (gid TEXT NOT NULL, uid TEXT NOT NULL, ts INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, acc REAL, PRIMARY KEY(gid,uid,ts));
CREATE TABLE groups (gid TEXT PRIMARY KEY, created_at INTEGER NOT NULL, last_activity INTEGER NOT NULL);
CREATE TABLE rate_limits (gid TEXT NOT NULL, uid TEXT NOT NULL, last_write INTEGER NOT NULL, PRIMARY KEY(gid,uid));
CREATE TABLE diag (gid TEXT NOT NULL, uid TEXT NOT NULL, session TEXT, ts INTEGER NOT NULL, e INTEGER NOT NULL, a REAL, b REAL, c REAL, s TEXT);
`;

function makeDb() {
  const db = new DatabaseSync(':memory:');
  db.exec(schema);
  const prepare = (sql) => {
    const numbered = /\?\d/.test(sql);
    const indexes = [...sql.matchAll(/\?(\d+)/g)].map((match) => Number(match[1]));
    const statement = db.prepare(numbered ? sql.replace(/\?(\d+)/g, '@p$1') : sql);
    let values = [];
    const params = () => {
      if (!numbered) return values;
      const result = {};
      indexes.forEach((index) => { result['p' + index] = values[index - 1]; });
      return result;
    };
    const api = {
      bind(...args) { values = args; return api; },
      run(...args) {
        if (args.length) values = args;
        const result = numbered ? statement.run(params()) : statement.run(...values);
        return { success: true, meta: { changes: result.changes } };
      },
      first() { return (numbered ? statement.get(params()) : statement.get(...values)) ?? null; },
      all() { return { results: numbered ? statement.all(params()) : statement.all(...values) }; },
    };
    return api;
  };
  return {
    prepare,
    batch(statements) { return Promise.all(statements.map((s) => s.run())); },
    close() { db.close(); },
  };
}

function env(db, extra = {}) {
  return { DB: db, ADMIN_SECRET: 'admin-secret', ...extra };
}

async function request(db, path, options = {}) {
  return worker.fetch(new Request(`https://example.test${path}`, options), env(db));
}

async function json(response) {
  return response.json();
}

async function createGroup(db, uid = 'alice') {
  const response = await request(db, '/api/create', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ uid, name: 'Alice' }),
  });
  return { response, body: await response.json() };
}

async function join(db, code, uid, name = uid, token = '') {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers['X-Member-Token'] = token;
  const response = await request(db, '/api/join', {
    method: 'POST',
    headers,
    body: JSON.stringify({ code, uid, name, token }),
  });
  return { response, body: await response.json() };
}

async function ingest(db, code, uid, token, values = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers['X-Member-Token'] = token;
  const response = await request(db, '/api/ingest', {
    method: 'POST',
    headers,
    body: JSON.stringify({ code, uid, token, lat: 49.84, lon: 24.03, t: Date.now(), ...values }),
  });
  return { response, body: await response.json() };
}

test('create and join provision independent member tokens', async () => {
  const db = makeDb();
  const created = await createGroup(db, 'alice');
  assert.equal(created.response.status, 200);
  assert.equal(created.body.token.length, 64);

  const joined = await join(db, created.body.code, 'bob');
  assert.equal(joined.response.status, 200);
  assert.ok(joined.body.token);
  assert.notEqual(joined.body.token, created.body.token);

  const noToken = await ingest(db, created.body.code, 'bob', '');
  assert.equal(noToken.response.status, 401);
  const wrongToken = await ingest(db, created.body.code, 'bob', '0'.repeat(64));
  assert.equal(wrongToken.response.status, 401);
  const accepted = await ingest(db, created.body.code, 'bob', joined.body.token);
  assert.equal(accepted.response.status, 200);
  db.close();
});

test('a member token cannot rename, leave, or ingest as another member', async () => {
  const db = makeDb();
  const created = await createGroup(db, 'alice');
  const joined = await join(db, created.body.code, 'bob');
  const before = await ingest(db, created.body.code, 'bob', joined.body.token);
  assert.equal(before.response.status, 200);

  const rename = await request(db, '/api/rename', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Member-Token': joined.body.token },
    body: JSON.stringify({ code: created.body.code, uid: 'alice', name: 'Stolen' }),
  });
  assert.equal(rename.status, 401);

  const leave = await request(db, '/api/leave', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Member-Token': joined.body.token },
    body: JSON.stringify({ code: created.body.code, uid: 'alice' }),
  });
  assert.equal(leave.status, 401);

  const spoofedIngest = await ingest(db, created.body.code, 'alice', joined.body.token, { lat: 1, lon: 1 });
  assert.equal(spoofedIngest.response.status, 401);
  db.close();
});

test('join supports POST JSON and returns a token; membership switch is isolated', async () => {
  const db = makeDb();
  const first = await createGroup(db, 'alice');
  const second = await createGroup(db, 'alice');
  const bobFirst = await join(db, first.body.code, 'bob');
  const bobSecond = await join(db, second.body.code, 'bob');
  assert.notEqual(bobFirst.body.token, bobSecond.body.token);
  assert.equal((await ingest(db, first.body.code, 'bob', bobSecond.body.token)).response.status, 401);
  assert.equal((await ingest(db, second.body.code, 'bob', bobFirst.body.token)).response.status, 401);
  assert.equal((await ingest(db, first.body.code, 'bob', bobFirst.body.token)).response.status, 200);
  db.close();
});

test('browser can create a viewer group and body tokens are rejected', async () => {
  const db = makeDb();
  const browser = await request(db, '/api/create', { method: 'POST' });
  assert.equal(browser.status, 200);
  assert.ok((await browser.json()).code);

  const created = await createGroup(db, 'alice');
  const joined = await join(db, created.body.code, 'bob');
  const bodyOnly = await request(db, '/api/ingest', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code: created.body.code, uid: 'bob', token: joined.body.token, lat: 1, lon: 1, t: Date.now() }),
  });
  assert.equal(bodyOnly.status, 401);
  db.close();
});

test('diagnostic reads require the separate admin secret', async () => {
  const db = makeDb();
  const created = await createGroup(db);
  const diag = await request(db, '/api/diag', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Member-Token': created.body.token },
    body: JSON.stringify({ code: created.body.code, uid: 'alice', events: [{ t: 1, e: 2, s: 'test' }] }),
  });
  assert.equal(diag.status, 200);

  const denied = await request(db, '/api/diag?code=' + created.body.code);
  assert.equal(denied.status, 401);
  const allowed = await request(db, '/api/diag?code=' + created.body.code, { headers: { 'X-Admin-Secret': 'admin-secret' } });
  assert.equal(allowed.status, 200);
  assert.equal((await allowed.json()).events.length, 1);
  db.close();
});

test('public errors redact internal failures and health details are minimal', async () => {
  const db = makeDb();
  const original = db.prepare;
  db.prepare = () => { throw new Error('secret internal path /private/data'); };
  const response = await request(db, '/api/health');
  db.prepare = original;
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true });

  db.prepare = () => { throw new Error('secret internal path /private/data'); };
  const failed = await request(db, '/api/join', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code: 'abcdefgh', uid: 'x', name: 'X' }),
  });
  db.prepare = original;
  assert.equal(failed.status, 500);
  assert.deepEqual(await failed.json(), { error: 'internal error' });
  db.close();
});

test('throttled ingest returns 429 and Retry-After without writing', async () => {
  const db = makeDb();
  const created = await createGroup(db);
  const first = await ingest(db, created.body.code, 'alice', created.body.token, { t: 1000 });
  assert.equal(first.response.status, 200);
  const second = await ingest(db, created.body.code, 'alice', created.body.token, { t: 2000 });
  assert.equal(second.response.status, 429);
  assert.equal(second.response.headers.get('Retry-After'), '8');
  assert.deepEqual(second.body, { error: 'rate limited', retryAfter: 8 });
  const row = db.prepare('SELECT lat FROM users WHERE uid = ?').bind('alice').first();
  assert.equal(row.lat, 49.84);
  db.close();
});

test('cleanup removes inactive groups, members, and their dependent records', async () => {
  const db = makeDb();
  const created = await createGroup(db);
  const gid = created.body.gid;
  const old = Date.now() - 40 * 24 * 60 * 60 * 1000;
  db.prepare('UPDATE users SET last_seen = ?, point_ts = ? WHERE gid = ?').bind(old, old, gid).run();
  db.prepare('UPDATE groups SET last_activity = ? WHERE gid = ?').bind(old, gid).run();
  db.prepare('INSERT INTO points (gid,uid,ts,lat,lon,acc) VALUES (?,?,?,?,?,?)').bind(gid, 'alice', old, 1, 1, 1).run();
  db.prepare('INSERT INTO diag (gid,uid,ts,e) VALUES (?,?,?,?)').bind(gid, 'alice', old, 1).run();
  const result = await worker.scheduled({}, env(db));
  assert.equal(result.deletedUsers, 1);
  assert.equal(result.deletedGroups, 1);
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM points').first().n, 0);
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM diag').first().n, 0);
  db.close();
});

test('request size and identifier bounds are enforced', async () => {
  const db = makeDb();
  const created = await createGroup(db);
  const huge = await ingest(db, created.body.code, 'alice', created.body.token, { name: 'x'.repeat(200000) });
  assert.equal(huge.response.status, 413);
  const longUid = await ingest(db, created.body.code, 'x'.repeat(129), created.body.token);
  assert.equal(longUid.response.status, 400);
  const longName = await join(db, created.body.code, 'alice', 'x'.repeat(100), created.body.token);
  assert.equal(longName.response.status, 200);
  assert.equal(longName.body.name.length, 24);
  db.close();
});
