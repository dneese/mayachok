#!/usr/bin/env node
// Звіт по діагностиці: що телефон робив і чому точки не йшли.
// Читає через публічний ендпоїнт Worker — без токенів і прямого доступу
// до бази.
//
//   node scripts/diag-report.mjs <код-групи> [uid]
//
// Код групи — те, що стоїть у посиланні на карту після #.

const API = 'https://mayachok.kikikiska.workers.dev';

const code = process.argv[2];
const uid = process.argv[3] || 'all';
if (!code) {
  console.error('вкажи код групи: node scripts/diag-report.mjs <код> [uid]');
  process.exit(1);
}

// Коди подій — дзеркало Diag.java
const EVENTS = {
  1: 'SERVICE_START сервіс піднято',
  2: 'SERVICE_STOP сервіс зупинено',
  3: 'STICKY_RESTART системний перезапуск',
  4: 'TASK_REMOVED змахнули з недавніх',
  5: 'SENSORS які датчики є',
  6: 'SIGMOTION_ARMED significant motion озброєно',
  7: 'SIGMOTION_FIRED significant motion спрацював',
  8: 'SIGMOTION_REARM перезброєння (обхід багу)',
  9: 'ACCEL_ON акселерометр увімкнено',
  10: 'ACCEL_OFF акселерометр вимкнено',
  11: 'MOTION_ON рух виявлено (акселерометр)',
  12: 'MOTION_OFF рух завершився',
  13: 'FIX_ACCEPTED точку прийнято',
  14: 'FIX_REJECTED точку відкинуто',
  15: 'GPS_ON GNSS увімкнено',
  16: 'GPS_OFF GNSS вимкнено',
  17: 'SEND_OK точку надіслано',
  18: 'SEND_FAIL не вдалося надіслати',
  19: 'SEND_SKIP рішення про надсилання',
  20: 'SKIP_NO_FRESH_FIX немає свіжої точки',
  21: 'SKIP_MOTION_PENDING чекаємо фікса',
  22: 'STALE_RETRY повтор через відсутність фікси',
  23: 'BYPASS обхідний інтервал',
  24: 'ENV пристрій і Doze',
  25: 'PERMISSION дозволи й оптимізація батареї',
  26: 'DRAINED буфер відправлено',
};

const fmtTime = (ts) => new Date(ts).toLocaleString('uk-UA', { hour12: false });

const adminSecret = process.env.MAYACHOK_ADMIN_SECRET;
if (!adminSecret) {
  console.error('встанови MAYACHOK_ADMIN_SECRET перед запуском');
  process.exit(1);
}

const res = await fetch(
  `https://mayachok.kikikiska.workers.dev/api/diag?code=${code}&uid=${uid}&limit=5000`,
  { headers: { 'x-admin-secret': adminSecret } },
);
const data = await res.json();
if (!data.ok) {
  console.error('помилка:', data.error ?? res.status);
  process.exit(1);
}

const events = data.events ?? [];
if (events.length === 0) {
  console.log('\nПодій немає. Перевірте: чи ввімкнена галочка діагностики на телефоні.');
  process.exit(0);
}

console.log(`\nКористувач: ${data.who ?? '—'}`);
console.log(`Подій: ${events.length}\n`);

// --- зведення ---
const counts = new Map();
for (const e of events) counts.set(e.e, (counts.get(e.e) ?? 0) + 1);
console.log('=== Скільки чого ===');
for (const [code, n] of [...counts].sort((x, y) => y[1] - x[1])) {
  console.log(`  ${String(n).padStart(5)}  ${EVENTS[code] ?? `код ${code}`}`);
}

// --- оточення ---
const env = events.find((e) => e.e === 24);
if (env) {
  console.log('\n=== Пристрій ===');
  console.log(`  ${env.s}`);
  console.log(`  батарея на старті: ${env.b}%`);
  console.log(`  оптимізація батареї вимкнена для застосунку: ${env.c === 1 ? 'так' : 'НІ ← ймовірна причина зупинок'}`);
}
const perm = events.find((e) => e.e === 25);
if (perm) {
  console.log('\n=== Дозволи й фони ===');
  console.log(`  геолокація: ${perm.a === 1 ? 'так' : 'ні'}`);
  console.log(`  режим енергозбереження: ${perm.b === 1 ? 'УВІМКНЕНО' : 'вимкнено'}`);
  console.log(`  обмеження фону: ${perm.c === 1 ? 'так (Android не дає прокидатися)' : 'ні'}`);
  console.log(`  ${perm.s}`);
}
const sensors = events.find((e) => e.e === 5);
if (sensors) {
  console.log('\n=== Датчики ===');
  console.log(`  акселерометр: ${sensors.a === 1 ? 'є' : 'НЕМАЄ'}`);
  const sig = sensors.b === 1 || /sig=так/.test(sensors.s || '');
  console.log(`  significant motion: ${sig ? 'є (дешевий режим)' : 'немає — працюємо на акселерометрі'}`);
  console.log(`  ${sensors.s}`);
}

// --- головне: надсилання ---
const sends = events.filter((e) => e.e === 17 || e.e === 18);
console.log(`\n=== Надсилань: ${sends.length} ===`);
const failed = sends.filter((e) => e.e === 18);
if (failed.length) {
  console.log(`  з них невдалих: ${failed.length}`);
  for (const f of failed.slice(0, 10)) console.log(`    ${fmtTime(f.ts)}  ${f.s}`);
} else if (sends.length) {
  const gaps = [];
  for (let i = 1; i < sends.length; i++) gaps.push((sends[i].ts - sends[i - 1].ts) / 60000);
  gaps.sort((a, b) => a - b);
  const max = gaps[gaps.length - 1] ?? 0;
  const med = gaps[Math.floor(gaps.length / 2)] ?? 0;
  console.log(`  пауз між точками: медіана ${med.toFixed(1)} хв, максимум ${max.toFixed(1)} хв`);
  if (max > 15) {
    const worst = gaps
      .map((g, i) => ({ g, at: sends[i + 1] }))
      .sort((x, y) => y.g - x.g)
      .slice(0, 3);
    console.log('  УВАГА: є розриви понад 15 хв — ось вони:');
    for (const w of worst) {
      console.log(`    ${fmtTime(w.at.ts)}  після мовчання ${w.g.toFixed(1)} хв`);
    }
  }
} else {
  console.log('  ЖОДНОГО надсилання немає. Див. нижче — чому.');
}

// --- чому не йшли точки ---
const noFix = events.filter((e) => e.e === 20);
const rejected = events.filter((e) => e.e === 14);
console.log(`\n=== Чому могло не йти ===`);
console.log(`  «немає свіжої точки»: ${noFix.length}`);
if (rejected.length) {
  const reasons = new Map();
  for (const r of rejected) {
    const key = r.s.replace(/[0-9.,]+/g, 'N').slice(0, 60);
    reasons.set(key, (reasons.get(key) ?? 0) + 1);
  }
  console.log(`  відкинутих фікса: ${rejected.length}`);
  for (const [why, n] of [...reasons].sort((a, b) => b[1] - a[1]).slice(0, 8)) {
    console.log(`    ${String(n).padStart(5)}×  ${why}`);
  }
}

// --- хронологія подій, які рідкі й важливі ---
const interesting = events.filter((e) => [2, 3, 4, 7, 8, 11, 12, 15, 16, 18, 23].includes(e.e));
console.log('\n=== Хронологія важливого ===');
for (const e of interesting.slice(-40)) {
  console.log(`  ${fmtTime(e.ts)}  ${EVENTS[e.e] ?? e.e}  ${e.s || ''}`);
}

const sessions = new Set(events.map((e) => e.session));
console.log(`\nСесій трекера: ${sessions.size}`);