-- Діагностика для 1.10: журнал технічних подій телефона.
--
-- Порядок накату: спершу Worker (він уже вміє /api/diag), потім цей файл.
-- Поки таблиці немає, Worker просто повертає помилку на цей ендпоінт, а
-- застосунок мовчки ігнорує її — трекінг при цьому працює як завжди.

CREATE TABLE IF NOT EXISTS diag (
  gid     TEXT NOT NULL,
  uid     TEXT NOT NULL,
  session TEXT,
  ts      INTEGER NOT NULL,
  e       INTEGER NOT NULL,
  a       REAL,
  b       REAL,
  c       REAL,
  s       TEXT
);

CREATE INDEX IF NOT EXISTS idx_diag_ts ON diag(ts);
CREATE INDEX IF NOT EXISTS idx_diag_uid ON diag(gid, uid, ts);