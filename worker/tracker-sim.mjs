// Симуляція автомата TrackerService: чи помічає телефон початок руху.
//
// Модель точно повторює умови вирішення з TrackerService.java. Сценарій:
//   0–20 хв  людина стоїть (GPS вмикається, потім засинає)
//   20 хв    людина починає йти; провайдери мовчать (замкнений простір)
//   20–40 хв йде
// Питання: за скільки хвилин після старту руху надійде перша нова точка?
//
// Запуск:  node ~/mayachok/worker/tracker-sim.mjs

const SEND_MIN_METERS = 100.0;
const STILL_METERS = 25.0;
const HEARTBEAT_MS = 10 * 60 * 1000;
const HEARTBEAT_MAX_MS = 30 * 60 * 1000;
const MAX_FIX_AGE_MS = 5 * 60 * 1000;
const GPS_WATCH_MIN_MS = 3 * 60 * 1000;
const GPS_WORTH_ACCURACY = 50;
const GPS_RESUME_DELAY_MS = 60 * 1000;
const INTERVAL_S = 60;

const goodAccuracy = 200;

function simulate({ label, walkStartMin, walkMetersPerMin = 70 }) {
  // Стан, дзеркальний TrackerService
  let haveFix = false;
  let lastFixAt = 0;
  let lastAcc = 0;
  let haveSent = false;
  let lastSentAt = 0;
  let lastSentMeters = 0; // пройдено від останньої відправки, у метрах
  let heartbeatMs = HEARTBEAT_MS;
  let watchUntil = 0;
  let gpsOffUntil = 0;
  let gpsActive = false;
  let fixMeters = 0; // координата фікса вздовж шляху, у метрах

  const sent = [];
  let firstSendMin = null;

  // Рух починається у walkStartMin; кожну хвилину — крок.
  const walkedBy = (tMin) =>
    tMin <= walkStartMin ? 0 : (tMin - walkStartMin) * walkMetersPerMin;

  const metersFromLastSent = () => Math.abs(fixMeters - lastSentMeters);
  const hasFreshFix = (now) => haveFix && now - lastFixAt <= MAX_FIX_AGE_MS;
  const gpsNeeded = (now) => {
    if (!hasFreshFix(now)) return true;
    if (lastAcc > GPS_WORTH_ACCURACY) return true;
    if (now < watchUntil) return true;
    return false;
  };

  const STEP_MS = 1000; // симулюємо по секунді — точність затримки до 1 с
  const END_MIN = 60;
  for (let t = STEP_MS; t <= END_MIN * 60 * 1000; t += STEP_MS) {
    // --- провайдери: GPS дає фікси тільки коли ввімкнений і ми рухаємось,
    //     «мережа» мовчить (замкнений простір) ---
    const walked = walkedBy(t / 60000);
    if (gpsActive && (walked > fixMeters + 20 || !haveFix)) {
      fixMeters = walked;
      lastFixAt = t;
      lastAcc = 5;
      haveFix = true;
      if (haveSent && Math.abs(fixMeters - lastSentMeters) >= SEND_MIN_METERS) {
        watchUntil = t + GPS_WATCH_MIN_MS;
      }
    }

    // --- таймер надсилання раз на інтервал ---
    if (t % (INTERVAL_S * 1000) !== 0) continue;

    // sendNow()
    if (!hasFreshFix(t)) {
      // startGpsIfWorth()
      if (!gpsActive && t >= gpsOffUntil && gpsNeeded(t)) gpsActive = true;
      // maybeStopGps()
      if (gpsActive && !gpsNeeded(t)) {
        gpsActive = false;
        gpsOffUntil = t + GPS_RESUME_DELAY_MS;
      }
      continue;
    }

    const moved = haveSent ? metersFromLastSent() : Infinity;
    if (haveSent && moved < STILL_METERS) heartbeatMs = Math.min(heartbeatMs * 2, HEARTBEAT_MAX_MS);
    else heartbeatMs = HEARTBEAT_MS;

    const shouldSend = !haveSent || moved >= SEND_MIN_METERS || t - lastSentAt >= heartbeatMs;
    if (shouldSend) {
      // відправка успішна
      lastSentAt = t;
      lastSentMeters = fixMeters;
      haveSent = true;
      watchUntil = t + Math.max(GPS_WATCH_MIN_MS, INTERVAL_S * 2000);
      sent.push(t / 60000);
      if (firstSendMin === null && t / 60000 >= walkStartMin) firstSendMin = t / 60000;
    }

    // maybeStopGps()
    if (gpsActive && !gpsNeeded(t)) {
      gpsActive = false;
      gpsOffUntil = t + GPS_RESUME_DELAY_MS;
    }
  }

  const delay = firstSendMin === null ? null : firstSendMin - walkStartMin;
  return { label, sent, delay, walkStartMin };
}

// --- Стара логіка (1.7), для порівняння ---
// Різниці: haveFix не старіє (перевірка !haveFix), немає watchUntil,
// GPS вмикається лише за рухом/неточністю, а stopGpsIfIdle знімає
// GPS одразу після відправки.
function simulateOld({ label, walkStartMin, walkMetersPerMin = 70 }) {
  const GPS_STALE_MS = 10 * 60 * 1000;
  let haveFix = false, lastFixAt = 0, lastAcc = 0, haveSent = false;
  let lastSentAt = 0, lastSentMeters = 0, heartbeatMs = HEARTBEAT_MS;
  let gpsOffUntil = 0, gpsActive = false, fixMeters = 0;

  const sent = [];
  let firstSendMin = null;
  const walkedBy = (tMin) =>
    tMin <= walkStartMin ? 0 : (tMin - walkStartMin) * walkMetersPerMin;
  const metersFromLastSent = () => Math.abs(fixMeters - lastSentMeters);

  const STEP_MS = 1000, END_MIN = 60;
  for (let t = STEP_MS; t <= END_MIN * 60 * 1000; t += STEP_MS) {
    const walked = walkedBy(t / 60000);
    if (gpsActive && (walked > fixMeters + 20 || !haveFix)) {
      fixMeters = walked; lastFixAt = t; lastAcc = 5; haveFix = true;
    }
    if (t % (INTERVAL_S * 1000) !== 0) continue;

    // sendNow() — зі старим `if (!haveFix)`, який ніколи не виконується
    if (!haveFix) {
      const movedOld = haveSent ? metersFromLastSent() : Infinity;
      const stale = !haveFix || t - lastFixAt > GPS_STALE_MS;
      if (!gpsActive && t >= gpsOffUntil && (stale || lastAcc > GPS_WORTH_ACCURACY || movedOld >= SEND_MIN_METERS)) {
        gpsActive = true;
      }
      continue;
    }

    const moved = haveSent ? metersFromLastSent() : Infinity;
    if (haveSent && moved < STILL_METERS) heartbeatMs = Math.min(heartbeatMs * 2, HEARTBEAT_MAX_MS);
    else heartbeatMs = HEARTBEAT_MS;
    const shouldSend = !haveSent || moved >= SEND_MIN_METERS || t - lastSentAt >= heartbeatMs;
    if (shouldSend) {
      lastSentAt = t; lastSentMeters = fixMeters; haveSent = true;
      sent.push(t / 60000);
      if (firstSendMin === null && t / 60000 >= walkStartMin) firstSendMin = t / 60000;
    }

    // stopGpsIfIdle()
    if (gpsActive) {
      const moved2 = haveSent ? metersFromLastSent() : Infinity;
      const stale2 = !haveFix || t - lastFixAt > GPS_STALE_MS;
      if (!stale2 && lastAcc <= GPS_WORTH_ACCURACY && moved2 < SEND_MIN_METERS) {
        gpsActive = false;
        gpsOffUntil = t + GPS_RESUME_DELAY_MS;
      }
    }
  }
  return { label, sent, delay: firstSendMin === null ? null : firstSendMin - walkStartMin };
}

console.log('Сценарій: людина 20 хв стоїть, потім йде; у замкненьому просторі\n');
const cases = [
  simulateOld({ label: 'стара логіка (щойно випущена 1.7)', walkStartMin: 20 }),
  simulate({ label: 'нова логіка (після виправлення)', walkStartMin: 20 }),
];
for (const r of cases) {
  console.log(`  ${r.label}`);
  console.log(`    надсигань: ${r.sent.map((x) => x.toFixed(1)).join(', ')}`);
  console.log(
    r.delay === null
      ? '    ПРОБЛЕМА: після початку руху точку не надіслав узагалі'
      : `    затримка після старту руху: ${r.delay.toFixed(1)} хв`,
  );
}