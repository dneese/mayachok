-- Міграція на складений ключ (gid, uid).
--
-- Стара схема мала uid PRIMARY KEY глобально: один пристрій міг належати
-- тільки одній групі назавжди, і при вході в іншу групу людина не з'являлася
-- на мапі. Тому таблицю перестворюємо і переносимо дані.
--
-- Ідемпотентно: можна запускати щоразу, дублі не з'являються (INSERT OR IGNORE).

DROP TABLE IF EXISTS users_new;

CREATE TABLE users_new (
  gid        TEXT NOT NULL,
  uid        TEXT NOT NULL,
  name       TEXT,
  role       TEXT NOT NULL DEFAULT 'tracker',
  last_seen  INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (gid, uid)
);

-- стари рядки не мають role — ті, хто надсилав точки, це трекери
INSERT OR IGNORE INTO users_new (gid, uid, name, role, last_seen, created_at)
  SELECT gid, uid, name, 'tracker', last_seen, created_at FROM users;

DROP TABLE users;
ALTER TABLE users_new RENAME TO users;

CREATE INDEX IF NOT EXISTS idx_users_gid ON users(gid);
CREATE INDEX IF NOT EXISTS idx_users_last_seen ON users(last_seen);
