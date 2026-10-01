-- Перенесення останньої точки з журналу в рядок учасника.
-- Виконується один раз; після цього points більше не поповнюється.
ALTER TABLE users ADD COLUMN lat REAL;
ALTER TABLE users ADD COLUMN lon REAL;
ALTER TABLE users ADD COLUMN acc REAL;
ALTER TABLE users ADD COLUMN bat INTEGER;
ALTER TABLE users ADD COLUMN point_ts INTEGER;

UPDATE users SET
  lat      = (SELECT p.lat FROM points p WHERE p.gid = users.gid AND p.uid = users.uid ORDER BY p.ts DESC LIMIT 1),
  lon      = (SELECT p.lon FROM points p WHERE p.gid = users.gid AND p.uid = users.uid ORDER BY p.ts DESC LIMIT 1),
  acc      = (SELECT p.acc FROM points p WHERE p.gid = users.gid AND p.uid = users.uid ORDER BY p.ts DESC LIMIT 1),
  bat      = (SELECT p.bat FROM points p WHERE p.gid = users.gid AND p.uid = users.uid ORDER BY p.ts DESC LIMIT 1),
  point_ts = (SELECT p.ts  FROM points p WHERE p.gid = users.gid AND p.uid = users.uid ORDER BY p.ts DESC LIMIT 1);
