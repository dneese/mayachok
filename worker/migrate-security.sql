-- Security hardening migration for member tokens, atomic write limits, and activity cleanup.
-- Deploy once to an existing D1 database.

ALTER TABLE users ADD COLUMN token_hash TEXT;
ALTER TABLE groups ADD COLUMN last_activity INTEGER;

UPDATE groups
SET last_activity = COALESCE(
  (SELECT MAX(u.last_seen) FROM users u WHERE u.gid = groups.gid),
  created_at
);

-- Existing memberships cannot be authenticated safely without a coordinated client
-- reset. Preserve their read-only locations but require them to re-provision.
UPDATE users SET token_hash = 'legacy-disabled' WHERE token_hash IS NULL;

CREATE INDEX IF NOT EXISTS idx_points_ts ON points(ts);

CREATE TABLE IF NOT EXISTS rate_limits (
  gid        TEXT NOT NULL,
  uid        TEXT NOT NULL,
  last_write INTEGER NOT NULL,
  PRIMARY KEY (gid, uid)
);
CREATE INDEX IF NOT EXISTS idx_rate_limits_last_write ON rate_limits(last_write);
