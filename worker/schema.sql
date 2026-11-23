-- gps-tracker D1 schema
-- gid = SHA-256(code)[0:16]. Group codes are never stored.

CREATE TABLE IF NOT EXISTS users (
  gid        TEXT NOT NULL,
  uid        TEXT NOT NULL,
  token_hash TEXT NOT NULL,
  name       TEXT,
  last_seen  INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  lat        REAL,
  lon        REAL,
  acc        REAL,
  bat        INTEGER,
  point_ts   INTEGER,
  PRIMARY KEY (gid, uid)
);
CREATE INDEX IF NOT EXISTS idx_users_gid ON users(gid);
CREATE INDEX IF NOT EXISTS idx_users_last_seen ON users(last_seen);

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
CREATE INDEX IF NOT EXISTS idx_points_ts ON points(ts);

CREATE TABLE IF NOT EXISTS groups (
  gid          TEXT PRIMARY KEY,
  created_at   INTEGER NOT NULL,
  last_activity INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS rate_limits (
  gid        TEXT NOT NULL,
  uid        TEXT NOT NULL,
  last_write INTEGER NOT NULL,
  PRIMARY KEY (gid, uid)
);
CREATE INDEX IF NOT EXISTS idx_rate_limits_last_write ON rate_limits(last_write);

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
