-- Міграція 1.15: повертаємо журнал точок — але на одну годину, а не назавжди.
--
-- У 1.7 таблицю points прибрали, бо журнал ріс безмежно: телефон писав точку
-- кожні кілька секунд, і база пухла як O(точок). Тепер інша ситуація:
-- телефон надсилає точку раз на інтервал (хвилини), сервер пише її сюди,
-- а cron видаляє старше за годину. Тож таблиця завжди ≈ O(учасників × година).
--
-- Це та сама схема, що й у schema.sql, тільки окремим файлом для накату
-- на живу базу:  bash scripts/migrate-d1.sh worker/migrate-1.15.sql

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
