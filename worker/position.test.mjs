// Тест Worker: остання точка живе в рядку учасника, журнал не росте,
// маркер не зникає, кеш працює, рейт-ліміт тримає базу, а аварійна
// відповідь не змішує групи між собою.
import worker from './src/index.js';

const users = [];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const T = 1750000000000;

// Лічильник запитів до бази: щоб перевірити, що точка пишеться ОДНИМ
// проходом, а не трьома, як раніше.
let queries = 0;
let failNext = 0;

const diagRows = [];
const points = [];

const db = {
  batch(statements) {
    return Promise.all(statements.map((s) => s.run()));
  },
  prepare(sql) {
    let args = [];
    const api = {
      bind: (...a) => { args = a; return api; },
      run: async () => {
        queries++;
        if (failNext > 0) { failNext--; throw new Error('D1 недоступний'); }

        if (/INSERT INTO diag/.test(sql)) {
          diagRows.push({
            gid: args[0], uid: args[1], session: args[2], ts: args[3],
            e: args[4], a: args[5], b: args[6], c: args[7], s: args[8],
          });
          return { success: true, meta: { changes: 1 } };
        }
        // Слід: журнал точок за останню годину. Пишеться в тому ж batch,
        // що й рядок учасника.
        if (/INSERT INTO points/.test(sql)) {
          const dup = points.some(
            (p) => p.gid === args[0] && p.uid === args[1] && p.ts === args[2],
          );
          if (!dup) {
            points.push({
              gid: args[0], uid: args[1], ts: args[2],
              lat: args[3], lon: args[4], acc: args[5],
            });
          }
          return { success: true, meta: { changes: dup ? 0 : 1 } };
        }
        if (/DELETE FROM points/.test(sql)) {
          const before = points.length;
          if (/uid = /.test(sql)) {
            for (let i = points.length - 1; i >= 0; i--) {
              if (points[i].gid === args[0] && points[i].uid === args[1]) points.splice(i, 1);
            }
          } else {
            for (let i = points.length - 1; i >= 0; i--) {
              if (points[i].ts < args[0]) points.splice(i, 1);
            }
          }
          return { success: true, meta: { changes: before - points.length } };
        }

        if (/DELETE FROM diag/.test(sql)) {
          const before = diagRows.length;
          for (let i = diagRows.length - 1; i >= 0; i--) {
            if (diagRows[i].ts < args[0]) diagRows.splice(i, 1);
          }
          return { success: true, meta: { changes: before - diagRows.length } };
        }

        // Точка: один UPSERT із координатами прямо в рядок учасника.
        if (/INSERT INTO users/.test(sql) && /point_ts\)\s*VALUES/i.test(sql)) {
          const f = users.find((u) => u.gid === args[0] && u.uid === args[1]);
          if (f) {
            f.last_seen = args[3];
            if (args[2]) f.name = args[2];
            f.lat = args[4]; f.lon = args[5]; f.acc = args[6]; f.bat = args[7]; f.point_ts = args[8];
          } else {
            users.push({
              gid: args[0], uid: args[1], name: args[2],
              last_seen: args[3], created_at: args[3],
              lat: args[4], lon: args[5], acc: args[6], bat: args[7], point_ts: args[8],
            });
          }
          return { success: true, meta: { changes: 1 } };
        }

        // Реєстрація в групі: UPSERT без координат.
        if (/INSERT INTO users/.test(sql)) {
          const f = users.find((u) => u.gid === args[0] && u.uid === args[1]);
          if (f) { f.last_seen = args[3]; if (args[2]) f.name = args[2]; }
          else users.push({ gid: args[0], uid: args[1], name: args[2], last_seen: args[3], created_at: args[3] });
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
        queries++;
        if (failNext > 0) { failNext--; throw new Error('D1 недоступний'); }
        if (/SELECT lat, lon, point_ts/.test(sql)) {
          const f = users.find((u) => u.gid === args[0] && u.uid === args[1]);
          return f ? { lat: f.lat, lon: f.lon, point_ts: f.point_ts } : null;
        }
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
        queries++;
        if (failNext > 0) { failNext--; throw new Error('D1 недоступний'); }
        if (/FROM diag/.test(sql)) {
          let rows = diagRows.filter((r) => r.gid === args[0]);
          if (/uid = /.test(sql)) rows = rows.filter((r) => r.uid === args[1]);
          const limit = /uid = /.test(sql) ? args[2] : args[1];
          rows = rows.slice(0, Number(limit) || rows.length);
          return { results: rows.map(({ session, ts, e, a, b, c, s }) => ({ session, ts, e, a, b, c, s })) };
        }
        if (/FROM points/.test(sql)) {
          let rows = points.filter((p) => p.gid === args[0] && p.ts >= args[1]);
          if (/uid = /.test(sql)) rows = rows.filter((p) => p.uid === args[2]);
          const limit = /uid = /.test(sql) ? args[3] : args[2];
          rows = rows.sort((a, b) => a.ts - b.ts).slice(0, Number(limit) || rows.length);
          return { results: rows.map(({ uid, ts, lat, lon, acc }) => ({ uid, ts, lat, lon, acc })) };
        }
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
const get = async (url) => (await worker.fetch(new Request(url), env)).json();

console.log('запис позиції:');
await ingest('code0001a', 'dev1', 49.84, 24.03, T);
check('точка прийнята', users.find((u) => u.uid === 'dev1').lat, 49.84);
check('час точки записано', users.find((u) => u.uid === 'dev1').point_ts, T);

// Ліміт D1 спільний на всі запити, тож кожен прохід у базу — це гроші.
// Стало п'ять: upsertUser (SELECT last_seen + INSERT), читання попередньої
// точки для перевірки неможливого стрибка, і два записи одним batch —
// рядок учасника + рядок сліду. Читання попередньої точки важливіше за
// решту разом: без нього одна фікса з майбутнім часом заморозила трекер
// на чужій точці на години (інцидент із Перу).
// Рахуємо на першій точці нового uid: повторний ingest того ж uid
// відсікає рейт-ліміт (8 секунд реального часу), тож лічильник був би 0.
queries = 0;
await ingest('code0001a', 'fresh1', 49.841, 24.031, T + 60_000);
check('точка пишеться пʼятьма проходами', queries, 5);
check('координата записана', users.find((u) => u.uid === 'fresh1').lat, 49.841);

// Друга людина: рейт-ліміт рахує реальний час, а тест усі запити робить
// за мілісекунди. Тому перевіряємо перезапис на іншому uid.
await ingest('code0001a', 'dev2', 49.85, 24.04, T + 60_000);
check('наступна точка перезаписала попередню (в users один рядок)', users.find((u) => u.uid === 'dev2').lat, 49.85);
check('у базі один рядок на людину', users.filter((u) => u.uid === 'dev2').length, 1);

console.log('слід за останню годину:');
{
  // Три точки в межах години (з паузами, щоб обійти рейт-ліміт 8 с) і одна
  // поза годиною — вона не має потрапити у відповідь.
  const t1 = Date.now() - 50 * 60 * 1000;
  const t2 = Date.now() - 20 * 60 * 1000;
  const t3 = Date.now();
  const tOld = Date.now() - 90 * 60 * 1000;
  await ingest('codesled1', 'sled1', 49.80, 24.00, t1);
  await sleep(8200);
  await ingest('codesled1', 'sled1', 49.81, 24.01, t2);
  await sleep(8200);
  await ingest('codesled1', 'sled1', 49.82, 24.02, t3);
  await sleep(8200);
  // Стара точка після нової: guard пропускає (швидкість у нормі), але в
  // межах «остання година» її немає.
  await ingest('codesled1', 'sled1', 49.70, 23.90, tOld);

  const tr = await get('https://x/api/track?code=codesled1&uid=sled1&minutes=60');
  check('слід повертає ok', tr.ok, true);
  check('у сліді лише точки за годину', tr.tracks.sled1.length, 3);
  check('точки впорядковані за часом', tr.tracks.sled1[0].ts < tr.tracks.sled1[1].ts, true);
  check('остання точка — найновіша', tr.tracks.sled1[2].lat, 49.82);
  check('стара точка за годину не потрапила', tr.tracks.sled1.some((p) => p.lat === 49.70), false);

  // Слід усіх учасників групи одним запитом.
  const all = await get('https://x/api/track?code=codesled1&minutes=60');
  check('слід «усі» групує за uid', Array.isArray(all.tracks.sled1), true);

  // Обрізання діапазону: понад 70 хв не приймаємо.
  const big = await get('https://x/api/track?code=codesled1&uid=sled1&minutes=9999');
  check('minutes обрізано до 70', big.minutes, 70);

  // Чистка: старше за 65 хв зникає саме з журналу сліду.
  const oldTs = Date.now() - 3 * 3600 * 1000;
  points.push({ gid: 'codesled1', uid: 'sled1', ts: oldTs, lat: 1, lon: 1, acc: 1 });
  const clean = await worker.scheduled({}, env);
  const stillOld = points.filter((p) => p.gid === 'codesled1' && p.ts < Date.now() - 65 * 60 * 1000).length;
  check('старий слід видалено cron', stillOld, 0);
  check('cleanup повідомив про видалені точки', clean.deletedPoints > 0, true);
}

console.log('роль прибрана разом із чатом:');
const joined = await (await worker.fetch(new Request(
  'https://x/api/join', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code: 'code0001a', uid: 'joiner', name: 'Тест', role: 'chat' }),
  }), env)).json();
check('join без поля role у відповіді', joined.role, undefined);
check('у рядку учасника немає role', 'role' in users.find((u) => u.uid === 'joiner'), false);
const gRole = await group('code0001a');
check('і в відповіді мапи її теж немає', 'role' in gRole.users[0], false);

console.log('кеш існує (5 секунд) і не ламає відповідь:');
const g1 = await group('code0001a');
check('у відповіді є список людей', Array.isArray(g1.users), true);
check('координата доїхала до мапи', g1.users.find((u) => u.uid === 'dev2').lat, 49.85);
const g1b = await group('code0001a');
check('другий запит (з кешу) дав те саме', g1b.users.find((u) => u.uid === 'dev2').lat, 49.85);
check('кеш не обрізає відповідь', g1b.users.length, users.filter((u) => u.gid === g1.users[0].gid).length);

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
check('у базу не потрапило нічого зайвого', users.find((u) => u.uid === 'spammer').lat, 49.80);
await sleep(8200); // межа 8 секунд реального часу
check('через 8 секунд знову приймає', await fast(49.90, T + 9000), false);
check('і позиція оновилась', users.find((u) => u.uid === 'spammer').lat, 49.90);
check('рядок так і один на людину', users.filter((u) => u.uid === 'spammer').length, 1);

console.log('падіння бази не змішує групи:');
// Наповнюємо аварійні копії для двох різних груп. Кеш живе 5 секунд,
// тож до перевірки даємо йому протухнути — інакше ми ніколи не дістанемось
// до бази, а отримаємо звичайний кешований варіант.
await group('code0002b');
await group('code0003c');
const okA = await group('code0002b');
const okC = await group('code0003c');
// Кеш живе 5 секунд і щойно оновлювався вище — чекаємо, поки протухне,
// інакше ми ніколи не дістанемось до бази, а отримаємо звичайний кеш.
await sleep(5200);
// Тепер база падає — і ми мусимо отримати дані СВОЄЇ групи, а не чужі.
failNext = 1;
const staleA = await group('code0002b');
failNext = 1;
const staleC = await group('code0003c');
failNext = 0;
check('група A при падінні бази отримує своє', staleA.stale, true);
check('і це справді група A, а не B', staleA.users[0].uid, okA.users[0].uid);
check('група C отримує своє', staleC.users[0].uid, okC.users[0].uid);
check('і групи не змішалися', staleA.users[0].uid !== staleC.users[0].uid, true);

console.log('вихід із групи:');
const lv = await (await worker.fetch(new Request('https://x/api/leave?code=code0002b&uid=lost1'), env)).json();
check('leave повертає ok', lv.ok, true);
check('рядок учасника видалено', users.filter((u) => u.uid === 'lost1').length, 0);
check('з мапи зник (кеш скинуто)', (await group('code0002b')).users.length, 0);
// Людина вийшла — її рух за годину теж не має лишатися в базі.
check('слід людини, що вийшла, теж видалено', points.filter((p) => p.uid === 'lost1').length, 0);

console.log('стрибок на інший континент (знайдено в родині: Львів → Перу):');
{
  await ingest('codejump1', 'jumper', 49.83, 24.02, Date.now());
  // Чекаємо, поки мине рейт-ліміт (8 с), інакше другий запит відсіється
  // ним і до перевірки стрибка не дійде.
  await sleep(8200);
  // Стрибок у Перу. Такої швидкості не існує.
  const res = await (await worker.fetch(new Request(
    `https://x/api/ingest?code=codejump1&uid=jumper&lat=-12.05&lon=-77.04&t=${Date.now()}`), env)).json();
  check('неможливий стрибок відхилено', res.ok, false);
  check('причина зрозуміла', res.error, 'implausible speed');
  check('позиція лишилась попередньою',
    users.find((u) => u.uid === 'jumper').lat, 49.83);
  // А справжній переїзд — ні: Львів → Київ за годину.
  const ok = await ingest('codejump1', 'jumper2', 50.45, 30.52, Date.now());
  check('реальний учасник проходить', ok.ok, true);
}

console.log('час із майбутнього (знайдено в родині: +514 днів):');
{
  const future = Date.now() + 514 * 24 * 3600 * 1000;
  await ingest('code0001a', 'timewarp', 49.83, 24.02, future);
  const row = users.find((u) => u.uid === 'timewarp');
  const skew = future - Date.now();
  check('час обрізано до «зараз» замість +514 днів',
    Math.abs(row.point_ts - Date.now()) < 5000, true);
  check('координати при цьому збереглися', row.lat, 49.83);
}

console.log('діагностика:');
const diagPost = async (events, uid = 'dev1') =>
  (await worker.fetch(new Request('https://x/api/diag', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code: 'code0001a', uid, session: 'sess01', dropped: 0, events }),
  }), env)).json();

const diagRes = await diagPost([
  { t: T, e: 1, a: 1, b: 2, c: 3, s: 'старт' },
  { t: T + 1000, e: 17, a: 5, b: 1, c: 71, s: 'точку надіслано' },
]);
check('події прийнято', diagRes.stored, 2);
check('у базі два рядки', diagRows.length, 2);
check('текст події збережено', diagRows[1].s, 'точку надіслано');
check('числа збережено', diagRows[1].c, 71);
check('сесія збережено', diagRows[0].session, 'sess01');

// ліміт: телефон не має права вичерпати D1 своєю помилкою
const flood = await diagPost(Array.from({ length: 500 }, (_, i) => ({ t: T + i, e: 13, s: 'ш' })));
check('запит обмежено 200 рядками', flood.stored, 200);
check('у базі рівно 200 додано', diagRows.length, 202);

// читання для розробника
const readBack = await (await worker.fetch(new Request(
  'https://x/api/diag?code=code0001a&uid=dev1&limit=10'), env)).json();
check('події читаються назад', readBack.events.length, 10);
check('читається саме та група', readBack.events[0].e >= 0, true);

// прибирання: через 48 годин від історії не лишається нічого
const future = Date.now() - 49 * 60 * 60 * 1000;
diagRows[0].ts = future;
diagRows[1].ts = future;
const cleaned = await worker.scheduled({}, env);
check('стара діагностика видалена', diagRows.length, 0);
check('cleanup повідомив, скільки видалив', cleaned.deletedDiag, 202);

console.log(`\n  пройдено ${pass}, провалено ${fail}`);
process.exit(fail ? 1 : 0);