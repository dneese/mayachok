-- gps-tracker D1 schema
-- gid = SHA-256(code)[0:16] — сам код до бази не потрапляє ніколи.

-- Ключ складений (gid, uid), а не лише uid: один і той самий телефон може
-- бути в кількох групах (родина + похід), і в кожній він має бути окремим учасником.
CREATE TABLE IF NOT EXISTS users (
  gid        TEXT NOT NULL,
  uid        TEXT NOT NULL,
  name       TEXT,
  -- Поле role (було 'tracker' або 'chat') прибране у версії 1.7 разом із чатом:
  -- учасників, які не передають координати, більше немає.
  last_seen  INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  -- Остання відома точка живе тут, у рядку учасника, а не в окремій
  -- таблиці журналу. Причини дві: база не росте від часу (O(учасників),
  -- а не O(точок)), і група бачить рівно те, що потрібно для пошуку
  -- людини — де вона востаннє була і коли.
  lat        REAL,
  lon        REAL,
  acc        REAL,
  bat        INTEGER,
  point_ts   INTEGER,  -- час останньої точки (не плутати з last_seen)
  PRIMARY KEY (gid, uid)
);
CREATE INDEX IF NOT EXISTS idx_users_gid ON users(gid);
CREATE INDEX IF NOT EXISTS idx_users_last_seen ON users(last_seen);

-- Слід за останню годину (версія 1.15).
--
-- У 1.7 цю таблицю прибрали, бо журнал pic точок ріс безмежно. Але без нього
-- не видно, звідки людина прийшла, — а саме це й просили. Тому повертаємо
-- журнал, але НЕ безмежний: код пише в нього кожну прийняту точку, а cron
-- вичищає все старше за TRAIL_RETENTION_MS (65 хв). Тобто таблиця завжди
-- тримає рівно останню годину, скільки б пристрій не працював.
--
-- Ключ (gid, uid, ts) заодно відкидає дублікати тієї самої мілісекунди.
CREATE TABLE IF NOT EXISTS points (
  gid  TEXT NOT NULL,
  uid  TEXT NOT NULL,
  ts   INTEGER NOT NULL,
  lat  REAL NOT NULL,
  lon  REAL NOT NULL,
  acc  REAL,
  PRIMARY KEY (gid, uid, ts)
);
CREATE INDEX IF NOT EXISTS idx_points_uid ON points(gid, uid, ts);

CREATE TABLE IF NOT EXISTS groups (
  gid        TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL
);

-- Діагностика: технічні події телефона, а не його координати.
--
-- Тимчасова таблиця: рядки живуть 48 годин (DIAG_RETENTION_MS) і видаляються
-- тим самим cron, що й решта. Вмикається користувачем у застосунку, за
-- замовчуванням не пишеться нічого.
CREATE TABLE IF NOT EXISTS diag (
  gid     TEXT NOT NULL,
  uid     TEXT NOT NULL,
  session TEXT,           -- ідентифікатор запуску трекера, для групування
  ts      INTEGER NOT NULL,
  e       INTEGER NOT NULL, -- код події, див. Diag.java
  a       REAL,            -- три числа-параметри події
  b       REAL,
  c       REAL,
  s       TEXT             -- короткий текст: причина, провайдер, помилка
);
CREATE INDEX IF NOT EXISTS idx_diag_ts ON diag(ts);
CREATE INDEX IF NOT EXISTS idx_diag_uid ON diag(gid, uid, ts);