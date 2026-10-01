// Тест Worker: остання точка живе в рядку учасника, журнал не росте,
// маркер не зникає, кеш працює, рейт-ліміт тримає базу.
import worker from './src/index.js';

const users = [];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const T = 1750000000000;

const db = {
  prepare(sql) {
    let args = [];
    const api = {
      bind: (...a) => { args = a; return api; },
      run: async () => {
        if (/INSERT INTO points/.test(sql)) return { success: true, meta: { changes: 1 } };
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
        if (/FROM users/.test(sql)) {
          return { results: users.filter((u) => u.gid === args[0]).map((u) => ({ ...u, ts: u.point_ts })) };
        }
        return { results: [] };
      },
    };
    return api;
  },
};

const env = { DB: db };
let pass = 0, fail = 0;
const check = (n, got, want) => {
  const ok = got === want;
  ok ? pass++ : fail++;
  console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${n}${ok ? '' : ` — ${got}, очікували ${want}`}`);
};
const ingest = async (code, uid, lat, lon, t) =>
  (await worker.fetch(new Request(
    `https://x/api/ingest?code=${code}&uid=${uid}&lat=${lat}&lon=${lon}&t=${t}`), env)).json();
const group = async (code) =>
  (await worker.fetch(new Request(`https://x/api/group?code=${code}`), env)).json();

console.log('запис позиції:');
check('точка прийнята', (await ingest('code0001a', 'dev1', 49.84, 24.03, T)).ok, true);
check('позиція лежить у рядку учасника', users.find((u) => u.uid === 'dev1').lat, 49.84);
check('час точки записано', users.find((u) => u.uid === 'dev1').point_ts, T);
// Друга людина: рейт-ліміт рахує реальний час, а тест усі запити робить
// за мілісекунди. Тому перевіряємо перезапис на іншому uid.
await ingest('code0001a', 'dev2', 49.85, 24.04, T + 60_000);
check('наступна точка перезаписала попередню (журналу немає)', users.find((u) => u.uid === 'dev2').lat, 49.85);
check('у базі один рядок на людину', users.filter((u) => u.uid === 'dev2').length, 1);

console.log('кеш існує (5 секунд) і не ламає відповідь:');
const g1 = await group('code0001a');
check('у відповіді є список людей', Array.isArray(g1.users), true);
check('координата доїхала до мапи', g1.users.find((u) => u.uid === 'dev2').lat, 49.85);
check('у групі двоє людей', g1.users.length, 2);
const g1b = await group('code0001a');
check('другий запит (з кешу) дав те саме', g1b.users.find((u) => u.uid === 'dev2').lat, 49.85);
check('кеш не обрізає відповідь', g1b.users.length, 2);

console.log('стара людина не зникає:');
const old = T - 40 * 3600 * 1000;
await ingest('code0002b', 'lost1', 49.85, 24.04, old);
const g2 = await group('code0002b');
check('через 40 годин учасник лишається в групі', g2.users.length, 1);
check('і його остання точка на місці', g2.users[0].lat, 49.85);
check('час останньої точки старий', g2.users[0].ts, old);

console.log('рейт-ліміт: зламаний клієнт не забиває базу:');
const fast = async (lat, t) => (await ingest('code0003c', 'spammer', lat, 24.0, t)).throttled === true;
check('перша точка проходить', await fast(49.80, T), false);
check('друга за мить відхилена', await fast(49.81, T + 500), true);
check('і третя теж', await fast(49.82, T + 1000), true);
// відхилені точки не мають змінювати навіть те, що вже є
check('у базу не потрапило нічого зайвого', users.find((u) => u.uid === 'spammer').lat, 49.80);
await sleep(8200); // межа 8 секунд реального часу
check('через 8 секунд знову приймає', await fast(49.90, T + 9000), false);
check('і позиція оновилась', users.find((u) => u.uid === 'spammer').lat, 49.90);
check('рядок так і один на людину', users.filter((u) => u.uid === 'spammer').length, 1);

console.log('вихід із групи:');
const lv = await (await worker.fetch(new Request('https://x/api/leave?code=code0002b&uid=lost1'), env)).json();
check('leave повертає ok', lv.ok, true);
check('рядок учасника видалено', users.filter((u) => u.uid === 'lost1').length, 0);
check('з мапи зник (кеш скинуто)', (await group('code0002b')).users.length, 0);

console.log(`\n  пройдено ${pass}, провалено ${fail}`);
process.exit(fail ? 1 : 0);
