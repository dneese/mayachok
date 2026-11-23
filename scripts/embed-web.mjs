// Вкладає статичну мапу (web/) у Worker як константу.
//
// Чому так: wrangler на Termux не запускається, а Pages тримає статику окремо
// від Worker. Тож асети їдуть тим самим модулем, що й код. Вивід склеюється
// з worker/src/index.js у scripts/deploy-worker.sh — індекс.js читає WEB
// через typeof-перевірку, тому в тестах (де WEB немає) усе працює як раніше.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

const TYPES = {
  '/index.html': 'text/html; charset=utf-8',
  '/app.js': 'application/javascript; charset=utf-8',
  '/core.js': 'application/javascript; charset=utf-8',
  '/style.css': 'text/css; charset=utf-8',
};

const web = {};
for (const [path, type] of Object.entries(TYPES)) {
  const body = readFileSync(join(root, 'web', path.slice(1)), 'utf8');
  web[path] = { type, body };
}

process.stdout.write('const WEB = ' + JSON.stringify(web) + ';\n');
