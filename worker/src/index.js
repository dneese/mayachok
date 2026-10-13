// gps-tracker Worker: прийом координат + API для мапи.
// Група визначається кодом; gid = SHA-256(code)[0:16]. Сам код ніде не зберігається.

// Остання точка живе в рядку учасника, тому «термін життя» позиції — це
// і є стільки днів, скільки живий рядок: 30. Це і та гарантія, що
// людину можна знайти після поїздки, і межа, за якою база не ростиме
// вічно. Раніше було навпаки — 30 хвилин, і людина зникала безслідно.
const MEMBER_RETENTION_DAYS = 30;
const GROUP_RETENTION_DAYS = 30; // група без активності теж зникає

// Економія запису: телефон сам надсилає точку лише тоді, коли зрушився
// (див. TrackerService), а last_seen ми тротлимо, бо інакше кожна
// перевірка «я в мережі» писала б у базу.
const LAST_SEEN_THROTTLE_MS = 5 * 60 * 1000;
// Маркер сіріє після 30 хв. Рівно стільки ж триває найдовший «подих»
// від телефона (див. HEARTBEAT_MAX_MS): якщо лежача людина ще не сіра, то
// її дані свіжі. Раніше було 15 — тоді вона б виглядала «невидимою»
// щохвилини після 15, хоча ми точно знаємо, де вона.
const FRESH_MINUTES = 30;
const MAX_NAME_LEN = 24;
// Швидкість, вище якої точка фізично неможлива.
// 1000 м/с = 3600 км/год — це вище швидкості будь-якого літака, тож
// справжню поїздку Guard не зачепить. Але стрибок на інший континент
// за лічильники секунд він зупинить.
const MAX_IMPLAUSIBLE_SPEED_MS = 1000;
// Діагностика живе два дні й зникає сама. Коротко тому, що це телеметрія
// з чужого телефону: навіть якщо про неї забудуть, вона не накопичиться.
const DIAG_RETENTION_MS = 48 * 60 * 60 * 1000;

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

/**
 * Кеш відповіді мапи в пам'яті інстансу Worker.
 *
 * Навіщо: D1 лімітований на 5 млн прочитаних рядків на добо, і без кешу
 * кожен глядач читав би базу окремо. 2 000 людей, які дивляться одну групу,
 * зробили б 2 000 читань замість одного на 5 секунд.
 *
 * Тримаємо й останню вдалу відповідь окремо: якщо D1 раптом недоступний
 * або вичерпав ліміт (тоді падають усі запити, включно з читанням), група
 * все одно показує останні відомі дані, а не порожню карту.
 */
const groupCache = new Map();   // gid -> { at, body }
// Аварійна копія відповіді — по gid, а не одна на всіх.
// Раніше це був один lastGoodBody на інстанс, тож при падінні D1 група A
// отримувала дані групи B (і бачила чужі точки). Тепер кожна група має
// власну останню вдалу відповідь.
const lastGood = new Map();     // gid -> body
const GROUP_CACHE_MS = 5000;

/**
 * Рейт-ліміт на запис координат.
 *
 * Навіщо це терміново, а не «для галочки»: ліміт D1 спільний на всі
 * запити, і коли він вичерпаний — падають УСІ запити, включно з читанням.
 * Тобто один телефон із зламаним циклом (або хтось, хто просто покрутив
 * кнопку 100 разів) гасить карту в усіх групах. Межа тримає такий випадок
 * від межі.
 *
 * Ліміт у пам'яті інстансу, без запису в базу: інакше ми б витрачали
 * запис, щоб захистити себе від запису.
 */
const writeGuard = new Map();  // uid -> час останнього прийнятого запису
const WRITE_MIN_GAP_MS = 8000; // менше 8 секунд між точками — це не людина

// Лічильники за поточним інстансом.
//
// Чесно про їхню цінність: вони живуть у пам'яті одного інстансу Worker, а
// Cloudflare розсипає запити по різних інстансах. Тому картина в /api/health
// часто нульова навіть після реального навантаження. Це НЕ придатний
// тригер для алерту.
//
// Надійний сигнал про наближення ліміту дає сам Cloudflare: лимит D1
// приходить поштою, а метрики — у розділі D1 → Metrics → Row Metrics.
// Тримаємо лічильники як підказку «щось тут відбулося», а не як охорону.
const meters = { writes: 0, throttled: 0, rejected: 0, cacheHits: 0, dbErrors: 0 };

function rateLimited(uid) {
  const last = writeGuard.get(uid);
  if (!last) return false;
  return Date.now() - last < WRITE_MIN_GAP_MS;
}

function markWrite(uid) {
  writeGuard.set(uid, Date.now());
  // Не даємо мапі розростися: забуваємо те, що мовчало понад хвилину.
  if (writeGuard.size > 5000) {
    for (const [key, at] of writeGuard) {
      if (Date.now() - at > 60_000) writeGuard.delete(key);
      if (writeGuard.size <= 4000) break;
    }
  }
}

function cacheGet(gid) {
  const hit = groupCache.get(gid);
  if (!hit) return null;
  // Повертаємо саме тіло відповіді, а не запис кешу — інакше клієнт
  // отримав би {at, body} замість списку людей.
  return Date.now() - hit.at < GROUP_CACHE_MS ? hit.body : null;
}

function cachePut(gid, body) {
  // Не даємо мапі розростися на весь акаунт: групи, які ніхто не дивиться,
  // давно мертві й нам не потрібні.
  if (groupCache.size > 500) {
    for (const [key, value] of groupCache) {
      if (Date.now() - value.at > GROUP_CACHE_MS) groupCache.delete(key);
      if (groupCache.size <= 400) break;
    }
  }
  groupCache.set(gid, { at: Date.now(), body });
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

/** Груба відстань у метрах — для перевірки стрибка точності не потрібно. */
function distanceMeters(lat1, lon1, lat2, lon2) {
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLon / 2) ** 2;
  return 2 * 6371000 * Math.asin(Math.min(1, Math.sqrt(a)));
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
async function handleJoin(request, db) {
  const params = await readParams(request);
  const gid = await groupIdFor(params.get('code'));
  if (!gid) return bad('invalid code');

  const uid = params.get('uid');
  if (!uid) return bad('uid required');

  const name = sanitizeName(params.get('name'));
  if (!name) return bad('name required');

  const member = await upsertUser(db, gid, uid, name);
  const user = await db
    .prepare('SELECT name FROM users WHERE gid = ?1 AND uid = ?2')
    .bind(gid, member)
    .first();
  if (!user) return json({ error: 'not found' }, 404);

  return json({ ok: true, uid: member, name: user.name, gid });
}

async function upsertUser(db, gid, uid, name) {
  const now = Date.now();
  if (uid) {
    // Існуючого користувача оновлюємо не частіше ніж раз на LAST_SEEN_THROTTLE_MS.
    // Цей запис ішов на кожну точку (2880/добу на людину) і сам по собі
    // виїдав базу за половину денного ліміту. Точність «онлайн» від цього
    // не страждає: дві хвилини нікого не хвилюють.
    const cleanName = sanitizeName(name);
    if (!cleanName) {
      // Тротлінг діє лише для точок. При вході в групу ім'я (і роль) треба
      // зберегти обов'язково, інакше перейменування зникло б на пару хвилин.
      const known = await db
        .prepare('SELECT last_seen FROM users WHERE gid = ?1 AND uid = ?2')
        .bind(gid, uid)
        .first();
      if (known && now - Number(known.last_seen) < LAST_SEEN_THROTTLE_MS) {
        return uid;
      }
    }
    // Ключ — пара (gid, uid): телефон може бути в кількох групах одночасно.
    // name перезаписуємо лише якщо прийшло непорожнє, щоб не стирати ім'я
    // при надсиланні точок без нього.
    const res = await db
      .prepare(
        `INSERT INTO users (gid, uid, name, last_seen, created_at)
         VALUES (?1, ?2, ?3, ?4, ?4)
         ON CONFLICT(gid, uid) DO UPDATE SET
           last_seen = ?4,
           name = COALESCE(?3, users.name)`,
      )
      .bind(gid, uid, cleanName || null, now)
      .run();
    if (res.success) return uid;
  }
  // Без uid (старий скрипт) — беремо першого учасника групи, як і раніше.
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
  // Не довіряємо годиннику телефону: у родині трапився фікса з часом на
  // 514 днів уперед, і вона «заморозила» трекер на чужій точці. Відсікаємо
  // все, що далі за хвилину в майбутньому (системний час може трохи
  // обганяти), і більше не приймаємо такий час узагалі.
  if (ts > Date.now() + 60 * 1000) ts = Date.now();

  const acc = isFiniteNumber(params.get('acc'), 0, 100000) ? Number(params.get('acc')) : null;
  const bat = isFiniteNumber(params.get('bat'), 0, 100) ? Math.round(Number(params.get('bat'))) : null;

  const requested = params.get('uid');
  if (requested && rateLimited(requested)) {
    meters.throttled++;
    return json({ ok: true, throttled: true, ts: Number(params.get('t')) || Date.now() });
  }

  const uid = await upsertUser(db, gid, requested, params.get('name'));

  // Захист від неможливого стрибка. Рахуємо на СЕРВЕРНОМУ часі: телефон
  // може надіслати будь-який час у тілі запиту, а ми вже обрізали ts до now
  // вище. Саме через це вдалося зловити інцидент, коли телефон приніс точку
  // з часом на 514 днів уперед, застосунок «замерз» на ній, а родина чотири
  // години бачила людину в Перу.
  //
  // Важливо: last_seen все одно оновлюємо. Людина може бути в дорозі, і
  // «щось пішло не так» не означає «не справжня людина».
  const previous = await db
    .prepare('SELECT lat, lon, point_ts FROM users WHERE gid = ?1 AND uid = ?2')
    .bind(gid, uid)
    .first();

  if (previous && previous.lat != null && previous.point_ts) {
    const seconds = (Date.now() - Number(previous.point_ts)) / 1000;
    if (seconds > 0) {
      // Називаємо не meters: глобальний лічильник має те саме ім'я, і
      // локальна змінна його перекривала б (через що лічильник падав).
      const jump = distanceMeters(
        Number(previous.lat), Number(previous.lon), Number(lat), Number(lon),
      );
      const speed = jump / seconds;
      if (speed > MAX_IMPLAUSIBLE_SPEED_MS) {
        meters.rejected++;
        return json({
          ok: false,
          error: 'implausible speed',
          speed: Math.round(speed),
        });
      }
    }
  }

  // Остання точка пишеться в рядок учасника, а не в журнал. Так база не
  // росте від часу: одна людина = один рядок назавжди, а не 2880 на добу.
  // Телефон надсилає точку лише тоді, коли зрушився (див. TrackerService).
  //
  // Раніше тут було три походи: upsertUser, SELECT name, UPDATE coords.
  // Тепер все це — один UPSERT. Менше і записів, і прочитаних рядків, а
  // ліміт D1 (100k writes / 5M reads на добу) спільний на всі запити.
  const res = await db
    .prepare(
      `INSERT INTO users (gid, uid, name, last_seen, created_at, lat, lon, acc, bat, point_ts)
       VALUES (?1, ?2, ?3, ?4, ?4, ?5, ?6, ?7, ?8, ?9)
       ON CONFLICT(gid, uid) DO UPDATE SET
         last_seen  = ?4,
         name       = COALESCE(?3, users.name),
         lat        = ?5,
         lon        = ?6,
         acc        = ?7,
         bat        = ?8,
         point_ts   = ?9`,
    )
    .bind(
      gid,
      uid,
      sanitizeName(params.get('name')) || null,
      Date.now(),
      Number(lat),
      Number(lon),
      acc,
      bat,
      ts,
    )
    .run();

  if (res.success) markWrite(uid);
  meters.writes++;
  return json({ ok: true, uid, ts });
}

async function handleGroup(db, gid) {
  // Свіжий кеш — віддаємо одразу, базу не чіпаємо.
  const hit = cacheGet(gid);
  if (hit) {
    meters.cacheHits++;
    return json(hit);
  }

  // Раніше тут стояв фільтр «ховати учасника через 30 хв» — і людина зникала
  // з карти безслідно. Тепер віддаємо всіх: стара точка лишається сірою
  // разом із часом «був онлайн», і за нею можна знайти людину.
  const { results } = await db
    .prepare(
      // Один прохід по таблиці учасників. Раніше позиція бралася п'ятьма
      // підзапитами на кожну людину — на 30 людях у групі це 150 рядків
      // прочитаних за один опитування мапи.
      `SELECT uid, name, last_seen, lat, lon, acc, bat, point_ts AS ts
       FROM users
       WHERE gid = ?1
       ORDER BY created_at`,
    )
    .bind(gid)
    .all();

  const body = {
    now: Date.now(),
    // Після скількох хвилин без точок маркер стає сірим. Не зникає —
    // просто видно, що це останнє відоме місце, а не «зараз тут».
    staleAfterMs: FRESH_MINUTES * 60 * 1000,
    // Скільки днів зберігаємо останню точку: далі її вже немає в базі.
    retentionDays: MEMBER_RETENTION_DAYS,
    // Усі учасники вікна, навіть без координат: щойно додана людина
    // має бути видна як «чекаємо GPS», а не зникати до першої точки.
    users: results,
  };

  cachePut(gid, body);
  lastGood.set(gid, body);
  // Аварійні копії не повинні рости безмежно: група, яку ніхто не дивиться,
  // нам не потрібна і після падіння D1.
  if (lastGood.size > 200) {
    const oldest = lastGood.keys().next();
    if (!oldest.done) lastGood.delete(oldest.value);
  }
  return json(body);
}

/**
 * Вихід із групи. Важливо, щоб це робило і сервер, а не лише телефон:
 * інакше людина «вийшла б» у себе на екрані, але її остання точка лишалася
 * б у базі й усі в групі бачили б, де вона була.
 */
async function handleLeave(request, db) {
  const params = await readParams(request);
  const gid = await groupIdFor(params.get('code'));
  if (!gid) return bad('invalid code');
  const uid = params.get('uid');
  if (!uid) return bad('uid required');

  const user = await db.prepare('DELETE FROM users WHERE gid = ?1 AND uid = ?2').bind(gid, uid).run();

  // Кеш скидаємо: людина вийшла — її не має бути на карті навіть на секунду.
  groupCache.delete(gid);
  lastGood.delete(gid);

  return json({
    ok: true,
    uid,
    deletedUser: (user.meta && user.meta.changes) || 0,
  });
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
  groupCache.delete(gid); // нове ім'я має з'явитися на карті одразу

  // Перевіряємо через SELECT, а не за лічильником змін — він нестабільний між версіями D1.
  const found = await db.prepare('SELECT name FROM users WHERE uid = ?1 AND gid = ?2').bind(uid, gid).first();
  if (!found) return json({ error: 'not found' }, 404);
  return json({ ok: true, uid, name: found.name });
}

async function handleHealth(db) {
  // Лічильники навмисно без запиту в базу: /api/health часто перевіряють
  // моніторингом, і не варто через це витрачати ліміт читання.
  return json({
    ok: true,
    time: Date.now(),
    // Нагадування про ліміти D1, щоб не тримати їх у голові:
    // 100 000 записів і 5 млн прочитаних рядків на добу, безкоштовно.
    meters,
    limits: { writesPerDay: 100000, rowsReadPerDay: 5000000 },
  });
}


/**
 * Діагностика від телефона: що він насправді робив і чому не надсилав точки.
 *
 * Увімкнена користувачем галочкою, за замовчуванням вимкнена. Координат
 * у ній немає — лише події та причини. Живе 48 годин і прибирається тим
 * самим cron, що й решта. Тому це не «пिशовик назавжди», а тимчасовий
 * інструмент, який легко собі набути й легко забути вимкнути.
 */
async function handleDiagIngest(request, db) {
  const body = await request.json().catch(() => null);
  if (!body) return bad('invalid json');

  const gid = await groupIdFor(body.code);
  if (!gid) return bad('invalid code');
  const uid = String(body.uid || '');
  if (!uid) return bad('uid required');

  const events = Array.isArray(body.events) ? body.events : [];
  // Жорсткий ліміт на запит: інакше помилка в телефоні або зловмисник
  // вичерпає денний ліміт запису D1 на всю систему.
  const limited = events.slice(0, 200);
  if (limited.length === 0) return json({ ok: true, stored: 0 });

  const session = String(body.session || '').slice(0, 16);
  const dropped = Number(body.dropped) || 0;

  const statements = limited.map((event) =>
    db
      .prepare(
        `INSERT INTO diag (gid, uid, session, ts, e, a, b, c, s)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)`,
      )
      .bind(
        gid,
        uid,
        session,
        Number(event.t) || Date.now(),
        Number(event.e) || 0,
        Number(event.a) || 0,
        Number(event.b) || 0,
        Number(event.c) || 0,
        String(event.s || '').slice(0, 200),
      ),
  );

  // Один batch замість N окремих запитів: інакше ми самі собі виїдаємо
  // ліміт D1 (100k записів на добу спільно на все).
  await db.batch(statements);
  meters.writes += statements.length;
  return json({ ok: true, stored: statements.length, dropped });
}

/** Читання діагностики — для розробника, а не для учасників групи. */
async function handleDiagRead(request, db) {
  const params = await readParams(request);
  const gid = await groupIdFor(params.get('code'));
  if (!gid) return bad('invalid code');

  const uid = params.get('uid');
  const limit = Math.min(Math.max(Number(params.get('limit')) || 500, 1), 5000);

  const base = 'SELECT session, ts, e, a, b, c, s FROM diag WHERE gid = ?1';
  const { results } =
    uid && uid !== 'all'
      ? await db.prepare(base + ' AND uid = ?2 ORDER BY ts ASC LIMIT ?3').bind(gid, uid, limit).all()
      : await db.prepare(base + ' ORDER BY ts ASC LIMIT ?2').bind(gid, limit).all();

  const names = await db
    .prepare('SELECT name FROM users WHERE gid = ?1 AND uid = ?2')
    .bind(gid, uid && uid !== 'all' ? uid : '')
    .first();

  return json({ ok: true, who: names ? names.name : null, events: results });
}

async function handleCleanup(db) {
  const diagCutoff = Date.now() - DIAG_RETENTION_MS;
  const usersCutoff = Date.now() - MEMBER_RETENTION_DAYS * 24 * 60 * 60 * 1000;
  const groupsCutoff = Date.now() - GROUP_RETENTION_DAYS * 24 * 60 * 60 * 1000;
  const b = await db.prepare('DELETE FROM users WHERE last_seen < ?1').bind(usersCutoff).run();
  const c = await db.prepare('DELETE FROM groups WHERE created_at < ?1').bind(groupsCutoff).run();
  // Діагностика — тимчасова: два дні, і вона видаляється сама, навіть якщо
  // про неї забули. Тому вона й не більша за добу в масовому режимі.
  const e = await db.prepare('DELETE FROM diag WHERE ts < ?1').bind(diagCutoff).run();
  return {
    deletedUsers: (b.meta && b.meta.changes) || 0,
    deletedGroups: (c.meta && c.meta.changes) || 0,
    deletedDiag: (e.meta && e.meta.changes) || 0,
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
      if (path === '/api/join') return await handleJoin(request, db);
      if (path === '/api/ingest') return await handleIngest(request, db);
      if (path === '/api/group') {
        // gid рахуємо тут, бо аварійна відповідь нижче шукається саме по ньому.
        // Раніше тут був спільний lastGoodBody на всі групи, тому змінна й не
        // потрібна була — але й чужі точки могли потрапити в чужу карту.
        const gid = await groupIdFor(url.searchParams.get('code'));
        if (!gid) return bad('invalid code');
        try {
          return await handleGroup(db, gid);
        } catch (error) {
          // D1 недоступний або вичерпав денний ліміт: тоді падають усі
          // запити, і без цієї гілки карта стала б порожньою в усіх.
          // Краще показати дані, яким трохи застаріли, ніж порожнечу.
          meters.dbErrors++;
          const good = lastGood.get(gid);
          if (good) {
            const stale = json({
              ...good,
              stale: true,
              staleReason: 'database unavailable',
            });
            stale.headers.set('Warning', '110 mayachok "Response is Stale"');
            return stale;
          }
          throw error;
        }
      }
      if (path === '/api/rename') return await handleRename(request, db);
      if (path === '/api/leave') return await handleLeave(request, db);
      if (path === '/api/diag' && request.method === 'POST') return await handleDiagIngest(request, db);
      if (path === '/api/diag') return await handleDiagRead(request, db);
      if (path === '/api/health') return await handleHealth(db);

      // Сумісність зі старим gps.php: ?lat=&lon=&t=
      if (url.searchParams.has('lat') && url.searchParams.has('lon')) return await handleIngest(request, db);
      if (url.searchParams.has('tracker')) return json({ ok: true });

      return json(
        {
          ok: false,
          endpoints: [
            '/api/create',
            '/api/join',
            '/api/ingest',
            '/api/group',
            '/api/rename',
            '/api/leave',
            '/api/diag',
            '/api/health',
          ],
        },
        404,
      );
    } catch (error) {
      return json({ error: 'internal', detail: String(error && error.message) }, 500);
    }
  },

  async scheduled(event, env) {
    return handleCleanup(env.DB);
  },
};
