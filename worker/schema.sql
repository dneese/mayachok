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

-- Таблиця points більше не ведеться й прибрана міграцією 1.7:
-- телефон надсилає координату тільки коли зрушився, а сервер тримає лише останню.

CREATE TABLE IF NOT EXISTS groups (
  gid        TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL
);