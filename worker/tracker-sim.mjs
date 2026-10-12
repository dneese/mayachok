// Симуляція автомата TrackerService: затримка помічання руху.
//
// Модель повторює умови вирішення з TrackerService.java, включно з
// акселерометром. Сценарій: телефон 20 хв лежить на столі (немає жодних
// GPS-фікса), потім людина бере його й іде.
//
// Питання: за скільки секунд після початку руху надійде перша нова точка?
//
//   node ~/mayachok/worker/tracker-sim.mjs

const SEND_MIN_METERS = 100.0;
const STILL_METERS = 25.0;
const HEARTBEAT_MS = 10 * 60 * 1000;
const HEARTBEAT_MAX_MS = 30 * 60 * 1000;
const MAX_FIX_AGE_MS = 5 * 60 * 1000;
const GPS_WATCH_MIN_MS = 3 * 60 * 1000;
const GPS_WORTH_ACCURACY = 50;
const GPS_RESUME_DELAY_MS = 60 * 1000;

// Акселерометр
const MOTION_ACCEL_MS2 = 0.35;
const MOTION_HITS = 3;
const QUIET_HITS = 5;
const SENSOR_PERIOD_MS = 200;

// GPS
const GPS_TTFF_MS = 25 * 1000; // холодний старт: «побачити» супутники
const MOTION_SEND_DELAY_MS = 12 * 1000;
const MOTION_RETRY_MS = 10 * 1000;

function run({ useAccelerometer, intervalS = 60, walkStartMin = 20, walkMetersPerMin = 70 }) {
  let haveFix = false, lastFixAt = 0, lastAcc = 0;
  let haveSent = false, lastSentAt = 0, lastSentMeters = 0;
  let heartbeatMs = HEARTBEAT_MS, watchUntil = 0, gpsOffUntil = 0;
  let gpsActive = false, fixMeters = 0, gpsTtffUntil = 0;

  let motionDetected = false, motionHits = 0, quietHits = 0, sensorOn = false;
  let motionSendAt = 0;

  const sent = [];
  let firstSendAfterWalk = null;

  const walkedBy = (tMin) =>
    tMin <= walkStartMin ? 0 : (tMin - walkStartMin) * walkMetersPerMin;
  const metersFromLastSent = () => Math.abs(fixMeters - lastSentMeters);
  const hasFreshFix = (now) => haveFix && now - lastFixAt <= MAX_FIX_AGE_MS;

  const gpsNeeded = (now) =>
    motionDetected ||
    !hasFreshFix(now) ||
    lastAcc > GPS_WORTH_ACCURACY ||
    now < watchUntil;

  const startGps = (t) => {
    if (gpsActive || t < gpsOffUntil || !gpsNeeded(t)) return;
    gpsActive = true;
    gpsTtffUntil = t + GPS_TTFF_MS;
  };
  const stopGps = (t) => {
    if (!gpsActive || gpsNeeded(t)) return;
    gpsActive = false;
    gpsOffUntil = t + GPS_RESUME_DELAY_MS;
  };
  const syncSensor = () => {
    sensorOn = !!useAccelerometer;
  };

  const doSend = (t) => {
    motionSendAt = 0;
    lastSentAt = t;
    lastSentMeters = fixMeters;
    haveSent = true;
    watchUntil = t + Math.max(GPS_WATCH_MIN_MS, intervalS * 2000);
    const tMin = t / 60000;
    sent.push(tMin);
    if (firstSendAfterWalk === null && tMin > walkStartMin) firstSendAfterWalk = tMin;
  };

  syncSensor();
  const STEP_MS = 100;
  const END_MIN = 45;
  for (let t = STEP_MS; t <= END_MIN * 60 * 1000; t += STEP_MS) {
    const walking = t / 60000 > walkStartMin;

    // --- акселерометр, кожні 200 мс ---
    if (sensorOn && t % SENSOR_PERIOD_MS === 0) {
      const acc = walking ? 2.5 : 0.02; // ходьба ~2,5 м/с²; на столі ~0
      if (acc > MOTION_ACCEL_MS2) {
        quietHits = 0;
        if (++motionHits >= MOTION_HITS && !motionDetected) {
          motionDetected = true;
          watchUntil = t + GPS_WATCH_MIN_MS;
          startGps(t);
          motionSendAt = t + MOTION_SEND_DELAY_MS;
        }
      } else {
        motionHits = 0;
        if (motionDetected && ++quietHits >= QUIET_HITS) motionDetected = false;
      }
    }

    // --- GPS-фікса ---
    if (gpsActive && t >= gpsTtffUntil && (walking || !haveFix)) {
      fixMeters = walkedBy(t / 60000);
      lastFixAt = t;
      lastAcc = 5;
      haveFix = true;
    }

    // --- надсилення за рухом (не чекаємо інтервалу) ---
    if (motionSendAt && t >= motionSendAt) {
      startGps(t);
      if (hasFreshFix(t)) doSend(t);
      else if (motionDetected) motionSendAt = t + MOTION_RETRY_MS;
      else motionSendAt = 0;
    }

    // --- звичайний таймер ---
    if (t % (intervalS * 1000) !== 0) continue;
    startGps(t);

    if (!hasFreshFix(t)) {
      stopGps(t);
      if (motionDetected) motionSendAt = t + MOTION_RETRY_MS;
      continue;
    }

    const moved = haveSent ? metersFromLastSent() : Infinity;
    if (haveSent && moved < STILL_METERS) heartbeatMs = Math.min(heartbeatMs * 2, HEARTBEAT_MAX_MS);
    else heartbeatMs = HEARTBEAT_MS;

    if (!haveSent || motionDetected || moved >= SEND_MIN_METERS || t - lastSentAt >= heartbeatMs) {
      doSend(t);
    }
    stopGps(t);
  }

  return {
    sent,
    delaySec: firstSendAfterWalk === null ? null : (firstSendAfterWalk - walkStartMin) * 60,
  };
}

for (const intervalS of [60, 300]) {
  console.log(`=== інтервал опитування: ${intervalS} с ===`);
  console.log('Телефон 20 хв лежить на столі, потім людина бере його й іде.\n');
  for (const cfg of [
    { useAccelerometer: false, label: 'лише GPS (1.7.1)', intervalS },
    { useAccelerometer: true, label: 'GPS + акселерометр (1.8)', intervalS },
  ]) {
    const r = run(cfg);
    console.log(`  ${cfg.label}`);
    console.log(`    надсигання, хв: ${r.sent.filter((x) => x <= 28).map((x) => x.toFixed(2)).join(', ')}`);
    console.log(
      r.delaySec === null
        ? '    ПРОБЛЕМА: після початку руху точку не надіслав узагалі'
        : `    затримка помічання руху: ${r.delaySec.toFixed(0)} с`,
    );
    console.log('');
  }
}