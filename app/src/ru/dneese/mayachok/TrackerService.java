package ru.dneese.mayachok;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.Log;

import java.util.Date;

/**
 * Foreground service: слухає локацію й раз на інтервал надсилає координати.
 * Працює з вимкненим екраном — саме тому це foreground service.
 *
 * Три провайдери, а не один:
 *  - GPS      — точний, але дорогий і не працює в приміщенні;
 *  - network  — за Wi-Fi/LTE, майже безкоштовний, але приблизний;
 *  - passive  — безкоштовний взагалі: не будить ні GPS, ні мережу, а просто
 *               підхоплює фікси, які система вже надіслала для інших застосунків.
 *
 * GPS не вмикається постійно: поки телефон стоїть і точка свіжа, GNSS-чіп спить.
 */
public final class TrackerService extends Service implements LocationListener {
  private static final String TAG = "GpsTracker";
  private static final String CHANNEL_ID = "gps_tracking";
  private static final int NOTIFICATION_ID = 42;

  // Умови, за яких телефон вважає, що точку варто надсилати.
  //
  // 100 метрів — це компроміс. Менше — телефон надсилає точку на кожному
  // кроці й GPS працює майже постійно. Більше — карта стає неточною там,
  // де треба знати, що людина вже дійшла.
  private static final double SEND_MIN_METERS = 100.0;
  // Нижче цієї відстані вважаємо, що телефон лежить нерухомо (дрейф GPS).
  private static final double STILL_METERS = 25.0;
  // «Подих», щоб група бачила, що людина жива, навіть коли не рухається.
  private static final long HEARTBEAT_MS = 10 * 60 * 1000L;
  // Лежить без руху — дихаємо ще рідше: до раз на півгодини.
  private static final long HEARTBEAT_MAX_MS = 30 * 60 * 1000L;

  // Старість фікса, якій ще можна довіряти. Далі — не надсилаємо нічого:
  // краще показати «очікуємо GPS», ніж видавати позавчорашню точку за теперішню.
  // Це водночас і стеля затримки: щойно фікса протухла, ми вмикаємо GPS
  // знову, щоб не чекати на heartbeat (до 30 хвилин).
  private static final long MAX_FIX_AGE_MS = 5 * 60 * 1000L;
  // Скільки дивимося «через плече» на рух після кожної відправки.
  // Щоб помітити початок руху, GPS має бути увімкнений саме в це вікно:
  // вимкнемо раніше — і дізнаємось про рух лише через heartbeat.
  // Не менше за два інтервали, інакше при інтервалі 5 хв фікса просто не встигне.
  private static final long GPS_WATCH_MIN_MS = 3 * 60 * 1000L;
  // Точність гірша за цю — GPS не дає нічого корисного, network не гірше.
  private static final float GPS_WORTH_ACCURACY = 50f;
  // «Прийнятна» точність. Добре (≤ GOOD_ACCURACY) — можна показувати як
  // позицію людини; гірше — лише якщо кращих даних у нас немає взагалі.
  private static final float GOOD_ACCURACY = 200f;
  // GPS не перезапитуємо частіше за цей час — інакше просто гріємо чіп.
  private static final long GPS_RESUME_DELAY_MS = 60 * 1000L;

// --- виявлення руху за акселерометром ---
  //
  // Навіщо, якщо вже є GPS: щоб зрозуміти, що телефон рухається, GPS-фікса
  // не потрібно. GNSS бачить лише наслідок (змінилась координата), а
  // акселерометр — причину. Тому телефон може зрозуміти «людина пішла» ще
  // тоді, коли супутників немає взагалі (підвал, метро), і одразу вмикати
  // GPS замість того, щоб чекати на heartbeat.
  //
  // Поріг навмисно такий малий: 0,35 м/с² — це ледь помітне зміщення,
  // набагато менше за крок людини. Щоб струснути стіл не вистачало.
  private static final float MOTION_ACCEL_MS2 = 0.35f;
  // Стільки зразків поспільшки мають перевищити поріг, щоб вважати рух.
  // Фільтр проти одноразових стрибків (стук у двері, автобус на гальмах).
  private static final int MOTION_HITS = 3;
  // А стільки тихих — щоб рух вважався завершеним.
  private static final int QUIET_HITS = 5;
  // Скільки чекати на фікса після руху, перш ніж надсилати точку.
  // GPS у відкритому небі бере 5–15 секунд; у машині й під дахом — більше.
  private static final long MOTION_SEND_DELAY_MS = 12 * 1000L;
  // Повторна спроба, якщо GPS не встиг на холодному старті.
  private static final long MOTION_RETRY_MS = 10 * 1000L;

  // --- significant motion ---
  //
  // TYPE_SIGNIFICANT_MOTION — це не потік даних, а одноразовий сигнал
  // «людина рухається». Працює він у сенсорній мікросхемі й сам будить
  // SoC, тож застосунку НЕ потрібен ані wake lock, ані постійне
  // слухання акселерометра. Норм споживання жорсткий: ≤0,5 мВт у спокої
  // і ≤1,5 мВт у русі (CDD 7.3).
  //
  // Тобто це дешевший за акселерометр спосіб дізнатися про рух.
  //
  // Важливо: сенсор одноразовий — після спрацювання Android сам його
  // скасовує, тому кожен раз треба registering заново.
  private static final long SIGMOTION_REARM_MS = 5 * 60 * 1000L;
  // Обхід раз на 15 хвилин: якщо сенсор зрештою залипне (див. обхід нижче)
  // або ми помилилися з оцінкою нерухомості, трекер не замикається назавжди.
  private static final long SIGMOTION_BYPASS_MS = 15 * 60 * 1000L;

  // --- фільтри фікса ---
  //
  // Швидкість понад 1285 км/год (357 м/с) — це не людина, а мультипроміння:
  // приймач бачить відбиття сигналу від будинків і вирішує, що супутників
  // більше, ніж є. GPSLogger відкидає такі точки outright (див. faq22).
  private static final float MAX_PLAUSIBLE_SPEED_MS = 357f;
  // GPS week rollover. Лічильник тижнів у навігаційному повідомленні має
  // лише 10 біт, тобто 1024 тижні ≈ 19,6 року. Старий чіп, у якого не
  // оновлено прошивку, після rollover віддає час на 1024 тижні в минуле.
  // Така точка пройшла б у базу як «2012 рік», а наш фільтр свіжості
  // відкидав би її далі — тобто точок не було б ніколи. Коригуємо.
  // Останній такий rollover: 6 квітня 2019 (наступний — 2038).
  private static final long GPS_ROLLOVER_BEFORE_MS = 1554508800000L; // 2019-04-06
  private static final long GPS_ROLLOVER_OFFSET_MS = 619315200000L; // 1024 тижні

  private LocationManager locationManager;
  // GPS-фікси приходять на gpsThread, таймер.sendNow() — на timerThread.
  // Різні лоопери навмисно: handler.removeCallbacksAndMessages(null) на таймері
  // не зносить доставку наступної точки від LocationManager.
  private HandlerThread gpsThread;
  private HandlerThread timerThread;
  private Handler gpsHandler;
  private Handler timer;
  private Api api;
  private Prefs prefs;

  private double lastLat;
  private double lastLon;
  private float lastAcc;
  private long lastFixAt; // час фікса, а не час його обробки
  private boolean haveFix;

  // Остання надіслана точка — щоб не ганяти запити, коли ми стоїмо на місці.
  // Ці поля пишемо з потоку Api, а читаємо з потоку таймера — тому volatile.
  private volatile boolean haveSent;
  private volatile double lastSentLat;
  private volatile double lastSentLon;
  private volatile long lastSentAt;
  private long heartbeatMs = HEARTBEAT_MS;
  private volatile int consecutiveFailures;

  // Точка, яку ми вже відправили: заповнюється перед запитом і живе до
  // його результату. Саме через це ми ніколи не «забуваємо» надіслати точку,
  // яка впала на мережі.
  private volatile boolean inFlight;

  private boolean gpsActive;
  private long gpsOffUntil;
  private volatile boolean timerRunning;

  // --- акселерометр ---
  private SensorManager sensorManager;
  private Sensor motionSensor; // linear acceleration: без сили тяжіння
  private Sensor significantMotion; // одноразовий тригер «людина рухається»
  private boolean sensorOn;
  private boolean significantArmed;
  private int motionHits;
  private int quietHits;
  private volatile boolean motionDetected;

  private String lastNotificationText = "";
  private long lastNotificationAt;

  @Override
  public void onCreate() {
    super.onCreate();
    api = new Api(this);
    prefs = new Prefs(this);
    locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    if (sensorManager != null) {
      // TYPE_LINEAR_ACCELERATION не включає силу тяжіння: на столі він
      // показує нуль, тож «зрушили» і «стоїть» не треба розрізняти вручну.
      // Де такого сенсора немає — візьмемо звичайний акселерометр і
      // віднімемо гравітацію самі.
      motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
      if (motionSensor == null) motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
      // Significant motion — дешевший тригер руху. Він wake-up: сам будить
      // SoC, тож не треба ні WakeLock, ні постійно відкритого акселерометра.
      // getDefaultSensor поверне null, якщо немає дозволу ACTIVITY_RECOGNITION
      // або такого сенсора на телефоні — тоді лишаємось на акселерометрі.
      significantMotion = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
    }
    if (motionSensor == null) Log.w(TAG, "акселерометра немає — визначатимемо рух лише за GPS");
    ensureChannel();
    startInForeground("Очікування сигналу GPS…");
  }

  /**
   * Користувач змахнув застосунок із недавніх.
   *
   * На звичайному Android це інформаційний виклик: сервіс працює далі. Але
   * частина виробників (Samsung, Xiaomi, Huawei) ставиться до змаху як до
   * примусової зупинки — вбиває процес і викидає START_STICKY-перезапуск.
   * Тоді трекер мовчить назавжди, поки хтось не відкриє застосунок руками.
   *
   * Різниця між OwnTracks і Hauk тут — саме ця помилка: на тих пристроях
   * це «підключитися за секунди» проти «не підключатися до наступного
   * відкриття застосунку». Ставимо будильник на 10 секунд, який піднімає
   * сервіс назад через PendingIntent (foreground service не можна підняти
   * просто з будильника).
   */
  @Override
  public void onTaskRemoved(Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
    if (!prefs.isTracking()) return; // користувач сам зупинив — не воскрешаємо
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

    Log.i(TAG, "застосунок змахнули — планую підйом сервіса через 10 с");

    Intent restart = new Intent(this, TrackerService.class);
    restart.setAction("start");
    PendingIntent pending =
        PendingIntent.getForegroundService(this, 7, restart, PendingIntent.FLAG_IMMUTABLE);

    AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
    if (alarms != null) alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 10_000L, pending);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent != null ? intent.getAction() : null;
    if ("stop".equals(action)) {
      stopSelf();
      return START_NOT_STICKY;
    }

    if (!prefs.configured()) {
      Log.w(TAG, "не налаштовано: код групи порожній");
      stopSelf();
      return START_NOT_STICKY;
    }

    // Потоки створюємо один раз. Інакше кожен повторний старт (наприклад,
    // після видалення дозволу) лишив би старий HandlerThread живим назавжди.
    if (gpsThread == null) {
      gpsThread = new HandlerThread("gps");
      gpsThread.start();
      gpsHandler = new Handler(gpsThread.getLooper());

      timerThread = new HandlerThread("gps-timer");
      timerThread.start();
      timer = new Handler(timerThread.getLooper());

      requestNetworkUpdates();
      startGpsIfWorth();
      // Відкриваємо вікно: одразу після старту акселерометр має зрозуміти,
      // чи телефон уже рухається — інакше перший рух буде помічено аж через
      // інтервал.
      watchUntil = SystemClock.elapsedRealtime() + GPS_WATCH_MIN_MS;
      updateMotionSensor();
      scheduleNextSend(0);
    } else if (!timerRunning) {
      // Сервіс підняли знову (sticky-перезапуск або змахнули з недавніх) —
      // таймер уже зупинено, тож піднімаємо його, не створюючи другого потоку.
      startGpsIfWorth();
      updateMotionSensor();
      // Fast first fix (Traccar SDK): при довгому інтервалі чекати на
      // перший спланований крок — це мовчання одразу після «Почати».
      // Просимо фікса негайно, щоб людина з'явилася на мапі за секунди.
      if (timer != null) timer.postDelayed(motionSendTask, MOTION_SEND_DELAY_MS);
      scheduleNextSend(0);
    }
    return START_STICKY;
  }

  /**
   * Network і passive — завжди. Обидва майже безкоштовні: мережа й так активна,
   * а passive не будить жодного провайдера. Саме вони врятують у підвалі,
   * де GPS не бачить жодного супутника.
   */
  private void requestNetworkUpdates() {
    requestUpdatesFor(LocationManager.NETWORK_PROVIDER, prefs.intervalSeconds() * 1000L, 0f);
    // Passive не має власного таймера: він віддає те, що вже є.
    requestUpdatesFor(LocationManager.PASSIVE_PROVIDER, 0L, 0f);
  }

  private void requestUpdatesFor(String provider, long minTime, float minDistance) {
    try {
      if (!hasLocationPermission()) {
        Log.w(TAG, "немає дозволу на геолокацію");
        stopSelf();
        return;
      }
      locationManager.requestLocationUpdates(provider, minTime, minDistance, this, gpsHandler.getLooper());
      Log.i(TAG, "слухаю " + provider);
    } catch (SecurityException error) {
      // Окремий провайдер може бути недоступний — тоді решта працює далі.
      Log.w(TAG, "відмовлено в доступі до " + provider + ": " + error.getMessage());
    } catch (IllegalArgumentException error) {
      // Провайдера немає на цьому телефоні. Неpanic — просто без нього.
      Log.w(TAG, "немає провайдера " + provider);
    }
  }

  private boolean hasLocationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
    return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
  }

  /**
   * GPS вмикаємо не завжди, а коли він має сенс: ми рухаємось, або свіжої
   * точки немає, або найкраща з наявних занадто груба.
   *
   * Економія тут найбільша: GNSS-чіп у спокої просто спить.
   */
  /**
   * Чи є фікса, якій ще можна довіряти як ПОТОЧНІЙ.
   *
   * Ключова різниця з haveFix: той прапорць стає істиною назавжди й лише
   * каже «фікс був колись». Тут миморимо час самої точки — старі дані
   * не можна ані показувати на карті як «зараз тут», ані перевіряти ними
   * «чи хтось рухається».
   */
private boolean hasFreshFix() {
     return haveFix && System.currentTimeMillis() - lastFixAt <= MAX_FIX_AGE_MS;
   }

  private final SensorEventListener motionListener =
      new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
          float x = event.values[0];
          float y = event.values[1];
          float z = event.values[2];

          if (Sensor.TYPE_ACCELEROMETER == event.sensor.getType()) {
            // Звичайний акселерометр містить і силу тяжіння: телефон, що лежить
            // на столі, «відчуває» постійні 9,8 м/с². Щоб не вважати це рухом,
            // віднімаємо гравітацію за напрямом вектора.
            float norm = (float) Math.sqrt(x * x + y * y + z * z);
            if (norm > 0.1f) {
              float k = 9.80665f / norm;
              x -= x * k;
              y -= y * k;
              z -= z * k;
            }
          }

          float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
          if (magnitude > MOTION_ACCEL_MS2) {
            quietHits = 0;
            if (++motionHits >= MOTION_HITS && !motionDetected) {
              motionDetected = true;
              onMotionStarted();
            }
          } else {
            motionHits = 0;
            if (motionDetected && ++quietHits >= QUIET_HITS) {
              motionDetected = false;
              onMotionStopped();
            }
          }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
      };

  /**
   * Почався рух: телефон лежав, а його взяли в руки, чи він у машині.
   *
   * Нової GPS-точки ще немає, але ми вже знаємо, що чекати на heartbeat
   * не варто, тож вмикаємо GNSS. Зазвичай фікса приходить за 10–30 секунд.
   */
  private void onMotionStarted() {
    watchUntil = SystemClock.elapsedRealtime() + GPS_WATCH_MIN_MS;
    Log.i(TAG, "рух за акселерометром — вмикаю GPS");
    updateNotification(notificationText(null));
    startGpsIfWorth();

    // Не чекаємо на звичайний інтервал: поки ми чекали б нього, людина
    // вже встигла б пройти сто метрів, і карта відставала б.
    // Даємо GPS короткий час на «побачити» супутники й одразу шлемо точку.
    if (timer == null) return;
    timer.removeCallbacks(motionSendTask);
    timer.postDelayed(motionSendTask, MOTION_SEND_DELAY_MS);
  }

  private final Runnable motionSendTask =
      new Runnable() {
        @Override
        public void run() {
          sendNow();
        }
      };

  private void onMotionStopped() {
    Log.i(TAG, "руху немає — телефон лежить");
  }

private final TriggerEventListener significantListener =
      new TriggerEventListener() {
        @Override
        public void onTrigger(TriggerEvent event) {
          // Спрацював — значить людина рухається. Сенсор після цього
          // одноразово скасовано системою, тож одразу залипаємо наново.
          significantArmed = false;
          Log.i(TAG, "significant motion: людина рухається");
          onMotionStarted();
          armSignificantMotion();
        }
      };

  /**
   * Вмикає одноразовий тригер руху.
   *
   * Тут дві хитрощі з реальних проєктів:
   *
   * 1. Реєструємо ДО перевірки обхідного інтервалу. Якщо перевірити спершу,
   *    ми втратимо подію, яка сталася рівно в цю мить (так зроблено в
   *    OwnTracks — коментар «реєстрація перед rate-limit, щоб не загубити
   *    подію»).
   *
   * 2. Кожні 5 хвилин робимо cancel + повторний запит. Відомий баг
   *    [issuetracker #259298110]: після тривалих періодів без спрацювання
   *    сенсор перестає реагувати навіть на реальний рух. Обхід саме такий,
   *    і він працює.
   */
  private synchronized void armSignificantMotion() {
    if (sensorManager == null || significantMotion == null) return;
    if (significantArmed) return;

    try {
      boolean ok = sensorManager.requestTriggerSensor(significantListener, significantMotion);
      if (!ok) return; // дозволу немає або сенсор недоступний
      significantArmed = true;

      // Пере-реєстрація через 5 хвилин (обхід #259298110)
      if (gpsHandler != null) {
        gpsHandler.removeCallbacks(rearmSignificant);
        gpsHandler.postDelayed(rearmSignificant, SIGMOTION_REARM_MS);
      }
    } catch (Exception error) {
      // SecurityException, якщо немає ACTIVITY_RECOGNITION, — просто
      // працюємо далі на акселерометрі.
      Log.w(TAG, "significant motion недоступний: " + error.getMessage());
      significantMotion = null;
    }
  }

  private final Runnable rearmSignificant =
      new Runnable() {
        @Override
        public void run() {
          if (significantArmed && sensorManager != null && significantMotion != null) {
            sensorManager.cancelTriggerSensor(significantListener, significantMotion);
            significantArmed = false;
          }
          armSignificantMotion();
        }
      };

  /**
   * Акселерометр працює весь час, поки ввімкнено трекер.
   *
   * Чому не «лише у вікні після відправки»: щоб помітити, що людину
   * взяли в руки, датчик має вже слухати. Якщо вимикати його в спокої,
   * перший же рух буде помічено аж на наступному тіку таймера — тобто
   * через інтервал (до 5 хвилин). Симуляція (worker/tracker-sim.mjs)
   * дає затримку 22 с проти 300 с саме через це.
   *
   * Якщо доступний significant motion, його подія перекриває потребу в
   * постійному акселерометрі, тож тоді ми його й не вмикаємо.
   */
  private synchronized void updateMotionSensor() {
    if (sensorManager == null || motionSensor == null || gpsHandler == null) return;
    armSignificantMotion();

    // Significant motion уже ввімкнений і сам про себе визначає рух.
    boolean canSkipAccelerometer = significantMotion != null;
    boolean wanted = !canSkipAccelerometer;
    if (wanted == sensorOn) return;

    if (wanted) {
      // SENSOR_DELAY_NORMAL ≈ 200 мс: достатньо, щоб не пропустити початок
      // ходьби, і дешево — сам датчик працює в сенсорній мікросхемі.
      sensorManager.registerListener(
          motionListener, motionSensor, SensorManager.SENSOR_DELAY_NORMAL, gpsHandler);
      sensorOn = true;
      Log.i(TAG, "акселерометр увімкнено: стежу за рухом");
    } else {
      sensorManager.unregisterListener(motionListener);
      sensorOn = false;
    }
  }

  /**
   * Чи варто взагалі тримати GNSS-чіп увімкненим.
   *
   * Тут була розсипана логіка, через яку рух міг «заснути» надовго: щоб
   * зрозуміти, що людина пішла, потрібна нова фікса, а нова фікса
   * можлива лише коли GPS увімкнений. Якщо вимкнути його одразу після
   * відправки — телефон дізнається про рух лише через heartbeat, тобто
   * через півгодини.
   *
   * Тому після КОЖНОЇ відправки ми ще дивимося «через плече» GPS_WATCH_MS:
   * цього вистачає, щоб помітити початок руху. Якщо за це вікно ніхто не
   * зрушив — GNSS-чіп справді спить далі.
   */
  private synchronized boolean gpsNeeded() {
    if (motionDetected) return true; // телефон рухається — точка потрібна зараз
    if (!hasFreshFix()) return true; // точки немає — треба її дістати
    if (lastAcc > GPS_WORTH_ACCURACY) return true; // остання занадто груба
    if (SystemClock.elapsedRealtime() < watchUntil) return true; // ще чекаємо руху
    return false;
  }

  private synchronized void startGpsIfWorth() {
    if (gpsActive || gpsHandler == null) return;
    if (SystemClock.elapsedRealtime() < gpsOffUntil) return;
    if (!gpsNeeded()) return;

    requestUpdatesFor(LocationManager.GPS_PROVIDER, prefs.intervalSeconds() * 1000L, 0f);
    gpsActive = true;
    // Рух триває: продовжуємо стежити за акселерометром, доки він не скінчиться.
    updateMotionSensor();

    // Холодний старт GPS триває до хвилини. Якщо фікса так і не буде —
    // повернемось пізніше, замість того щоб розпрягати чіп назавжди.
    gpsHandler.postDelayed(
        new Runnable() {
          @Override
          public void run() {
            maybeStopGps();
          }
        },
        GPS_RESUME_DELAY_MS);
  }

  /** GNSS можна вимкнути: ніхто не рухається і точка свіжа. */
  private synchronized void maybeStopGps() {
    if (!gpsActive) return;
    if (gpsNeeded()) return;

    try {
      locationManager.removeUpdates(this);
    } catch (Exception ignored) {
      // провайдера вже немає в системі
    }
    // removeUpdates знімає ВСІ реєстрації цього слухача, а не лише GPS.
    // Тому network і passive доводиться переписати — інакше вони зникли б
    // разом із GPS і в приміщенні ми знову стали б «мовчазними».
    requestNetworkUpdates();
    gpsActive = false;
    // Не намагаємося одразу: інакше «стою — вмик — вимк» ганяє GNSS без кінця.
    gpsOffUntil = SystemClock.elapsedRealtime() + GPS_RESUME_DELAY_MS;
    Log.i(TAG, "GPS вимкнено: стоїмо, точка свіжа");
  }

  // Вікно, протягом якого ми ще чекаємо початку руху після відправки.
  // Пишемо з потоку Api (після відправки) і з потоку GPS (при русі),
  // читаємо з таймера — тому volatile.
  private volatile long watchUntil;
  // Коли востаннє робили «обхідний» фікс, коли рух не помічено.
  private long lastBypassAt;

  @Override
  public void onLocationChanged(Location location) {
    // Деякі провайдери (а на Android 16 — і з вагомо) присилають null.
    // Без перевірки це падіння на кожному оновленні, тобто сервіс впаде.
    if (location == null) return;

    long now = System.currentTimeMillis();
    long fixTime = adjustedFixTime(location.getTime(), now);

    // Точка від пасивного провайдера може бути сирою копією чужої фікса.
    // Вічно «свіжий» час у такій точці змусив би нас думати, що ми рухаємось.
    if (now - fixTime > MAX_FIX_AGE_MS) return;

    String provider = location.getProvider();
    float incoming = location.hasAccuracy() ? location.getAccuracy() : 9999f;

    // Мультипроміння: приймач вирішує, що супутників більше, ніж є, і
    // телепортує людину на іншу сторону міста. Швидкість вирахуємо самі —
    // location.getSpeed() GPSЛамає саме на такому шумі.
    if (haveFix) {
      double seconds = (fixTime - lastFixAt) / 1000.0d;
      if (seconds > 0) {
        double meters = metersFrom(location.getLatitude(), location.getLongitude());
        if (meters / seconds > MAX_PLAUSIBLE_SPEED_MS) {
          Log.w(TAG, "неправдоподібна швидкість — точку відкидаю");
          return;
        }
      }
    }

    boolean fresher = fixTime > lastFixAt;
    boolean muchBetter = incoming + 100f < lastAcc;

    // Обираємо, чи замінювати наявну точку новою.
    //
    // Простий варіант «новіша — значить краща» тут небезпечний: стоячи в
    // одному місці, мережа може тримати точку за 1–2 км (округлення до
    // вишки), і ми відсилали б «порожній» маркер. Тому коли в нас уже є
    // добра точка (accuracy ≤ 200 м), нам гіршу пропонувати не приймаємо,
    // і замість цього просимо GPS.
    boolean incomingGood = incoming <= GOOD_ACCURACY;
    boolean currentGood = haveFix && lastAcc <= GOOD_ACCURACY;
    boolean accept;
    if (!haveFix) accept = true;
    else if (incomingGood && !currentGood) accept = true; // краще за те, що є
    else if (!incomingGood && currentGood) accept = false; // погірше — ігноруємо
    else accept = fresher || muchBetter; // обидві прийнятні — беремо новішу

    if (!accept) return;

    lastLat = location.getLatitude();
    lastLon = location.getLongitude();
    lastAcc = incoming;
    lastFixAt = fixTime;
    haveFix = true;

    updateNotification(notificationText(provider));

    // Зрушили — продовжуємо дивитися за рухом, інакше GPS вимкнеться саме
    // в моменті, коли людина почала йти.
    if (haveSent && metersFrom(lastSentLat, lastSentLon) >= SEND_MIN_METERS) {
      watchUntil = SystemClock.elapsedRealtime() + GPS_WATCH_MIN_MS;
    }
    startGpsIfWorth();
  }

  /**
   * Час фікса з поправкою на GPS week rollover.
   *
   * Лічильник тижнів у старому навігаційному повідомленні має лише 10 біт,
   * тобто 1024 тижні ≈ 19,6 року. Чіп зі старою прошивкою після rollover
   * віддає час на 1024 тижні в минуле. Без поправки така точка або потрапила
   * б у базу як «2012 рік», або (гірше) назавжди відкидалася б нашим фільтром
   * свіжості — і точок не було б ніколи.
   */
  private static long adjustedFixTime(long reported, long now) {
    if (reported > 0 && reported < GPS_ROLLOVER_BEFORE_MS) return reported + GPS_ROLLOVER_OFFSET_MS;
    return reported;
  }

  @Override
  public void onProviderEnabled(String provider) {
    if (LocationManager.GPS_PROVIDER.equals(provider)) {
      updateNotification("GPS увімкнено, чекаю сигнал…");
    }
  }

  @Override
  public void onProviderDisabled(String provider) {
    if (LocationManager.GPS_PROVIDER.equals(provider)) updateNotification("GPS вимкнено");
  }

  @Override
  public void onStatusChanged(String provider, int status, Bundle extras) {}

  /** Груба відстань у метрах; для «рушився чи ні» більшої точності не треба. */
  private double metersFrom(double lat, double lon) {
    double dLat = (lat - lastSentLat) * Math.PI / 180.0d;
    double dLon = (lon - lastSentLon) * Math.PI / 180.0d;
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(lastSentLat * Math.PI / 180.0d)
                * Math.cos(lat * Math.PI / 180.0d)
                * Math.sin(dLon / 2)
                * Math.sin(dLon / 2);
    return 2 * 6371000.0d * Math.asin(Math.min(1.0d, Math.sqrt(a)));
  }

  /**
   * Надсилаємо точку не кожні intervalSeconds, а лише коли є що сказати.
   *
   * GPS безкоштовний, а мобільний інтернет і запис у базу — ні. Тому
   * рішення приймає сам телефон: якщо людина стоїть на місці або повільно
   * йде, ми не ганяємо запити в порожнечу.
   */
  private void sendNow() {
    if (inFlight) return; // попередній запит ще в дорозі — не плодимо другий

    long now = System.currentTimeMillis();

    // Свіжість, а не «фікс був колись». Прапорець haveFix живе вічно, тож
    // на нього спиратися не можна: стара точка — це не поточна позиція.
    if (!hasFreshFix()) {
      // Немає свіжої точки — не надсилаємо стару, а просимо нову.
      // Без цього телефон просто чекав би на heartbeat, тобто до 30 хвилин.
      startGpsIfWorth();
      maybeStopGps();
      // Рух ми вже помітили, але GPS ще не встиг: намір «надіслати» не
      // можна втрачати, бо інакше точка піде аж на наступному тіку таймера.
      if (motionDetected && timer != null) {
        timer.removeCallbacks(motionSendTask);
        timer.postDelayed(motionSendTask, MOTION_RETRY_MS);
      }
      return;
    }

    // Датчики тримаємоarmed завжди, доки ввімкнено трекер.
    updateMotionSensor();

    // Обхід significant motion. Сенсор може злипнути (відомий баг
    // #259298110), а ми можемо помилитися з оцінкою нерухомості. Тому
    // раз на 15 хвилин робимо фікс, навіть якщо руху не бачимо — інакше
    // трекер замикається назавжди. Так само робить GPSLogger.
    boolean bypass = now - lastBypassAt >= SIGMOTION_BYPASS_MS;
    if (bypass) {
      lastBypassAt = now;
      watchUntil = SystemClock.elapsedRealtime() + GPS_WATCH_MIN_MS;
      startGpsIfWorth();
    }

    double moved = haveSent ? metersFrom(lastSentLat, lastSentLon) : Double.MAX_VALUE;

    // Лежить нерухомо — розтягуємо «подих» аж до півгодини.
    if (haveSent && moved < STILL_METERS) {
      heartbeatMs = Math.min(heartbeatMs * 2, HEARTBEAT_MAX_MS);
    } else {
      heartbeatMs = HEARTBEAT_MS;
    }

    // Рух — головна підстава надсилати. Коли людина йде або їде, карта має
    // йти за нею, а не чекати закриття вікна серцебиття.
    boolean send =
        !haveSent
            || motionDetected
            || moved >= SEND_MIN_METERS
            || now - lastSentAt >= heartbeatMs;
    if (!send) {
      maybeStopGps();
      return;
    }

    inFlight = true;
    final double lat = lastLat;
    final double lon = lastLon;
    final float acc = lastAcc;
    final long fixAt = lastFixAt;

    api.send(
        lat,
        lon,
        acc,
        fixAt,
        new Api.Callback() {
          @Override
          public void onResult(String error) {
            inFlight = false;
            if (error != null) {
              // Головне тут: точку НЕ вважаємо надісланою. Вона лишається
              // невідісланою й піде на наступному тіку. Раніше стан
              // оновлювався до запиту, тож одна обривка мережі замикала
              // надсилання на півгодини.
              consecutiveFailures++;
              heartbeatMs = HEARTBEAT_MS;
              prefs.setLastSendError(error);
              Log.w(TAG, "надсилання не вдалося: " + error);
              updateNotification("Не вдалося надіслати: " + error);
              return;
            }

            if (consecutiveFailures > 0) Log.i(TAG, "мережа ожила");
            consecutiveFailures = 0;
            prefs.setLastSendError("");

            // Коміт лише після успіху.
            lastSentAt = System.currentTimeMillis();
            lastSentLat = lat;
            lastSentLon = lon;
            haveSent = true;
            prefs.setLastSentAt(lastSentAt);

            // Відкриваємо вікно спостереження за рухом. Без цього maybeStopGps()
            // вимкнув би GNSS одразу після відправки, і ми дізналися б про рух
            // лише тоді, коли протухне фікса — тобто за 5 хвилин, а в спокої
            // так і не дізналися б (див. GPS_WATCH_MIN_MS).
            watchUntil =
                SystemClock.elapsedRealtime()
                    + Math.max(
                        GPS_WATCH_MIN_MS, prefs.intervalSeconds() * 2000L);

            Log.i(TAG, "точку надіслано");
            updateNotification(notificationText(LocationManager.GPS_PROVIDER));
            maybeStopGps();
          }
        });
  }

  private String notificationText(String provider) {
    if (!haveFix) return "Очікування сигналу GPS…";
    String time = DateFormat.getTimeFormat(this).format(new Date(lastFixAt));
    String quality = lastAcc > 100f ? "приблизна (±" + Math.round(lastAcc) + " м)" : "±" + Math.round(lastAcc) + " м";
    String source = LocationManager.NETWORK_PROVIDER.equals(provider) ? ", за мережею"
        : LocationManager.PASSIVE_PROVIDER.equals(provider) ? ", пасивна" : "";
    return "Остання точка " + time + " · " + quality + source;
  }

  /**
   * Наступна спроба. Коли надсилання не вдалося — відходимо все далі
   * (від 2 до 8 інтервалів), щоб не бити в стіну, коли мережа лежить.
   * Вдалі — повертаємося до звичайного інтервалу.
   */
  private void scheduleNextSend(final long delayMs) {
    if (timer == null) return;
    timerRunning = true;
    timer.removeCallbacksAndMessages(null);
    timer.postDelayed(
        new Runnable() {
          @Override
          public void run() {
            sendNow();
            long base = prefs.intervalSeconds() * 1000L;
            if (consecutiveFailures > 0) {
              long backoff = base * (1L << Math.min(consecutiveFailures, 3));
              scheduleNextSend(backoff + jitter());
            } else {
              // Невеликий випадковий зсув: щоб тисяча телефонів, увімкнених
              // о 9:00, не вдарили в базу в ту саму мить.
              scheduleNextSend(base + jitter());
            }
          }
        },
        delayMs);
  }

  /** Випадковий зсув до 20% інтервалу. */
  private long jitter() {
    long base = prefs.intervalSeconds() * 1000L;
    return (long) (Math.random() * base * 0.2d);
  }

  private void ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
    NotificationManager manager =
        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) return;
    NotificationChannel channel =
        new NotificationChannel(CHANNEL_ID, "Трекінг GPS", NotificationManager.IMPORTANCE_LOW);
    channel.setShowBadge(false);
    channel.setDescription("Показує, що трекер активний");
    manager.createNotificationChannel(channel);
  }

  private void startInForeground(String text) {
    lastNotificationText = text;
    Notification notification = buildNotification(text);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
          NOTIFICATION_ID,
          notification,
          android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
    } else {
      startForeground(NOTIFICATION_ID, notification);
    }
  }

  /**
   * Оновлюємо сповіщення не на кожній точці, а не частіше разу на хвилину
   * й лише коли текст реально змінився: кожен notify() — це binder-виклик
   * і пробудження системи, а вигляду це не додає.
   */
  private synchronized void updateNotification(String text) {
    long now = SystemClock.elapsedRealtime();
    if (text.equals(lastNotificationText)) return;
    if (now - lastNotificationAt < 60 * 1000L) return;
    lastNotificationText = text;
    lastNotificationAt = now;

    NotificationManager manager =
        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
  }

  private Notification buildNotification(String text) {
    Intent open = new Intent(this, MainActivity.class);
    open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      flags |= PendingIntent.FLAG_IMMUTABLE;
    }
    PendingIntent pending = PendingIntent.getActivity(this, 0, open, flags);

    return new Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Трекер активний")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentIntent(pending)
        .setOngoing(true)
        .build();
  }

  @Override
  public void onDestroy() {
    if (gpsHandler != null) gpsHandler.removeCallbacksAndMessages(null);
    if (timer != null) timer.removeCallbacksAndMessages(null);
    if (gpsThread != null) gpsThread.quitSafely();
    if (timerThread != null) timerThread.quitSafely();
    try {
      if (locationManager != null) locationManager.removeUpdates(this);
    } catch (Exception ignored) {
      // сервіс уже відсутній у системі
    }
    // Акселерометр обов'язково знімаємо: забутий слухач датчика — це
    // постійна витрата батареї, поки застосунок у фоні.
    try {
      if (sensorManager != null) {
        sensorManager.unregisterListener(motionListener);
        if (significantArmed && significantMotion != null) {
          sensorManager.cancelTriggerSensor(significantListener, significantMotion);
        }
      }
    } catch (Exception ignored) {
      // датчик уже відключений
    }
    sensorOn = false;
    significantArmed = false;

    // Екран показує «Трекер активний» саме за цим прапорцем. Без скидання
    // убитий процес лишав би зелену крапку при мертвому надсиланні — саме
    // через це було незрозуміло, надсилає застосунок точки чи ні.
    prefs.setTracking(false);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}