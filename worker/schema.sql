-- gps-tracker D1 schema
-- gid = SHA-256(code)[0:16] — сам код до бази не потрапляє ніколи.

CREATE TABLE IF NOT EXISTS users (
  uid        TEXT PRIMARY KEY,
  gid        TEXT NOT NULL,
  name       TEXT,
  last_seen  INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_users_gid ON users(gid);

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
