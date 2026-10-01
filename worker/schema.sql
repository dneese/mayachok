-- gps-tracker D1 schema
-- gid = SHA-256(code)[0:16] — сам код до бази не потрапляє ніколи.

-- Ключ складений (gid, uid), а не лише uid: один і той самий телефон може
-- бути в кількох групах (родина + похід), і в кожній він має бути окремим учасником.
CREATE TABLE IF NOT EXISTS users (
  gid        TEXT NOT NULL,
  uid        TEXT NOT NULL,
  name       TEXT,
  role       TEXT NOT NULL DEFAULT 'tracker', -- 'tracker' надсилає GPS, 'chat' — лише пише
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

-- Журнал точок більше НЕ пишеться: телефон надсилає координату тільки коли
-- зрушився, а сервер тримає лише останню. Таблиця лишається лише для
-- одноразового перенесення старих даних і буде видалена окремим запитом.
CREATE TABLE IF NOT EXISTS points (
  id  INTEGER PRIMARY KEY AUTOINCREMENT,
  uid TEXT NOT NULL,
  gid TEXT NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  acc REAL,
  bat INTEGER,
  ts  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_points_uid_ts ON points(uid, ts);
CREATE INDEX IF NOT EXISTS idx_points_gid_ts ON points(gid, ts);

CREATE TABLE IF NOT EXISTS groups (
  gid        TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL
);

-- Чат групи. Лише текст, без файлів. Тиждень історії (видаляється у cleanup).
CREATE TABLE IF NOT EXISTS messages (
  id    INTEGER PRIMARY KEY AUTOINCREMENT,
  gid   TEXT NOT NULL,
  uid   TEXT NOT NULL,
  name  TEXT,
  body  TEXT NOT NULL,
  ts    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_gid_id ON messages(gid, id);
CREATE INDEX IF NOT EXISTS idx_messages_ts ON messages(ts);
