// Тест: остання точка живе в рядку учасника, журнал не росте,
// last_seen тротлиться, вихід із групи стирає людину.
import worker from './src/index.js';

const users = [];
let pointInserts = 0;

const db = {
  prepare(sql) {
    let args = [];
    const api = {
      bind: (...a) => { args = a; return api; },
      run: async () => {
        if (/INSERT INTO points/.test(sql)) { pointInserts++; return { success: true, meta: { changes: 1 } }; }
        if (/INSERT INTO users/.test(sql)) {
          const f = users.find((u) => u.gid === args[0] && u.uid === args[1]);
          if (f) { f.last_seen = args[4]; if (args[2]) f.name = args[2]; }
          else users.push({ gid: args[0], uid: args[1], name: args[2], role: args[3], last_seen: args[4], created_at: args[4] });
          return { success: true, meta: { changes: 1 } };
        }
        if (/UPDATE users SET lat/.test(sql)) {
          const f = users.find((u) => u.gid === args[0] && u.uid === args[1]);
          if (f) { f.lat = args[2]; f.lon = args[3]; f.acc = args[4]; f.bat = args[5]; f.point_ts = args[6]; }
          return { success: true, meta: { changes: 1 } };
        }
        if (/DELETE FROM points/.test(sql)) return { success: true, meta: { changes: 0 } };
        if (/DELETE FROM users/.test(sql)) {
          const i = users.findIndex((u) => u.gid === args[0] && u.uid === args[1]);
          if (i >= 0) users.splice(i, 1);
          return { success: true, meta: { changes: i >= 0 ? 1 : 0 } };
        }
        return { success: true, meta: { changes: 0 } };
      },
      first: async () => {
        if (/SELECT last_seen FROM users/.test(sql)) {
          const f = users.find((u) => u.gid === args[0] && u.uid === args[1]);
          return f ? { last_seen: f.last_seen } : null;
        }
        if (/SELECT name FROM users/.test(sql)) {
          const f = users.find((u) => u.gid === args[0] && u.uid === args[1]);
          return f ? { name: f.name } : null;
        }
        if (/SELECT uid FROM users/.test(sql)) return users[0] ? { uid: users[0].uid } : null;
        return null;
      },
      all: async () => {
        if (/FROM users/.test(sql)) return { results: users.filter((u) => u.gid === args[0]).map((u) => ({ ...u, ts: u.point_ts })) };
        return { results: [] };
      },
    };
    return api;
  },
};

const env = { DB: db };
const code = 'test1234test';
let pass = 0, fail = 0;
const check = (n, got, want) => { const ok = got === want; ok ? pass++ : fail++; console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${n}${ok ? '' : ` — ${got}, очікували ${want}`}`); };
const send = async (lat, lon, t) => {
  const u = `https://x/api/ingest?code=${code}&uid=dev1&lat=${lat}&lon=${lon}&t=${t}`;
  return (await (await worker.fetch(new Request(u), env)).json());
};

console.log('запис позиції:');
const T = 1750000000000;
check('точка прийнята', (await send(49.84, 24.03, T)).ok, true);
check('позиція лежить у рядку учасника', users[0].lat, 49.84);
check('час точки записано', users[0].point_ts, T);
await send(49.85, 24.04, T + 60000);
check('наступна точка перезаписала попередню (журналу немає)', users[0].lat, 49.85);
check('у базі один рядок учасника', users.length, 1);
check('жодного запису в points', pointInserts, 0);

console.log('map API:');
const g = await (await worker.fetch(new Request(`https://x/api/group?code=${code}`), env)).json();
check('учасника видно у відповіді', g.users.length, 1);
check('координата доїхала до мапи', g.users[0].lat, 49.85);
check('поле ts заповнене', g.users[0].ts, T + 60000);

console.log('стара людина не зникає:');
users[0].last_seen = T - 60 * 60 * 1000 * 40; // 40 годин без точок
users[0].point_ts = T - 60 * 60 * 1000 * 40;
const g2 = await (await worker.fetch(new Request(`https://x/api/group?code=${code}`), env)).json();
check('через 40 годин учасник лишається в групі', g2.users.length, 1);
check('і його остання точка на місці', g2.users[0].lat, 49.85);

console.log('вихід із групи:');
const lv = await (await worker.fetch(new Request(`https://x/api/leave?code=${code}&uid=dev1`), env)).json();
check('leave повертає ok', lv.ok, true);
check('рядок учасника видалено', users.length, 0);
const g3 = await (await worker.fetch(new Request(`https://x/api/group?code=${code}`), env)).json();
check('з мапи зник', g3.users.length, 0);

console.log(`\n  пройдено ${pass}, провалено ${fail}`);
process.exit(fail ? 1 : 0);
