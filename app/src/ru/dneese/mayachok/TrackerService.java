package ru.dneese.mayachok;

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
  private static final long MAX_FIX_AGE_MS = 5 * 60 * 1000L;
  // Якщо остання точка старша за це — вмикаємо GPS навіть у спокої.
  private static final long GPS_STALE_MS = 10 * 60 * 1000L;
  // Точність гірша за цю — GPS не дає нічого корисного, network не гірше.
  private static final float GPS_WORTH_ACCURACY = 50f;
  // GPS не перезапитуємо частіше за цей час — інакше просто гріємо чіп.
  private static final long GPS_RESUME_DELAY_MS = 60 * 1000L;

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

  private String lastNotificationText = "";
  private long lastNotificationAt;

  @Override
  public void onCreate() {
    super.onCreate();
    api = new Api(this);
    prefs = new Prefs(this);
    locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    ensureChannel();
    startInForeground("Очікування сигналу GPS…");
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
      scheduleNextSend(0);
    } else if (!timerRunning) {
      // Сервіс підняли знову (sticky-перезапуск) — таймер уже зупинено,
      // тож піднімаємо його, не створюючи другого потоку.
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
  private synchronized void startGpsIfWorth() {
    if (gpsActive || gpsHandler == null) return;
    long now = SystemClock.elapsedRealtime();
    if (now < gpsOffUntil) return;

    double moved = haveSent ? metersFrom(lastSentLat, lastSentLon) : Double.MAX_VALUE;
    boolean stale = !haveFix || System.currentTimeMillis() - lastFixAt > GPS_STALE_MS;
    boolean inaccurate = haveFix && lastAcc > GPS_WORTH_ACCURACY;

    if (!stale && !inaccurate && moved < SEND_MIN_METERS) return;

    requestUpdatesFor(LocationManager.GPS_PROVIDER, prefs.intervalSeconds() * 1000L, 0f);
    gpsActive = true;

    // Холодний старт GPS може тривати хвилину. Якщо ми зупинимось через
    // 60 секунд і фікса так і не буде — повернемось пізніше, замість того
    // щоб розпрягати чіп назавжди.
    gpsHandler.postDelayed(
        new Runnable() {
          @Override
          public void run() {
            stopGpsIfIdle();
          }
        },
        GPS_RESUME_DELAY_MS);
  }

  /** GPS можна вимкнути: ми стоїмо, точка свіжа й достатньо точна. */
  private synchronized void stopGpsIfIdle() {
    if (!gpsActive) return;
    double moved = haveSent ? metersFrom(lastSentLat, lastSentLon) : Double.MAX_VALUE;
    boolean stale = !haveFix || System.currentTimeMillis() - lastFixAt > GPS_STALE_MS;
    boolean inaccurate = haveFix && lastAcc > GPS_WORTH_ACCURACY;
    if (stale || inaccurate || moved >= SEND_MIN_METERS) return;

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

  @Override
  public void onLocationChanged(Location location) {
    long now = System.currentTimeMillis();
    // Точка від пасивного провайдера може бути сирою копією чужої фікса.
    // Вічно «свіжий» час у такій точці змусив би нас думати, що ми рухаємось.
    if (now - location.getTime() > MAX_FIX_AGE_MS) return;

    String provider = location.getProvider();
    // Network-фікса зазвичай точніша за дрейф GPS у приміщенні, але програє
    // справжньому GPS на вулиці. Тому беремо найкраще з наявного.
    if (haveFix && !isBetterThanCurrent(location)) {
      // Нічого не змінилось — лише оновлюємо час фікса, щоб freshness рахувався
      // від свіжої інформації, а не від часу обробки.
      if (now - lastFixAt > MAX_FIX_AGE_MS) lastFixAt = location.getTime();
      return;
    }

    lastLat = location.getLatitude();
    lastLon = location.getLongitude();
    lastAcc = location.hasAccuracy() ? location.getAccuracy() : 9999f;
    lastFixAt = location.getTime();
    haveFix = true;

    updateNotification(notificationText(provider));
    // Рух ⇒ GPS знову потрібен, щоб точність не відставала.
    if (provider != null && LocationManager.GPS_PROVIDER.equals(provider)) startGpsIfWorth();
  }

  /** Чи ця точка краща за ту, що вже маємо. */
  private boolean isBetterThanCurrent(Location location) {
    float incoming = location.hasAccuracy() ? location.getAccuracy() : 9999f;
    // Свіжість важливіша за точність: прострочені дані не мають сенсу.
    boolean incomingFresher = location.getTime() > lastFixAt + 1000L;
    if (incomingFresher && incoming < 200f) return true;
    return incoming + 100f < lastAcc;
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
    if (!haveFix) {
      startGpsIfWorth();
      return;
    }

    double moved = haveSent ? metersFrom(lastSentLat, lastSentLon) : Double.MAX_VALUE;

    // Лежить нерухомо — розтягуємо «подих» аж до півгодини.
    if (haveSent && moved < STILL_METERS) {
      heartbeatMs = Math.min(heartbeatMs * 2, HEARTBEAT_MAX_MS);
    } else {
      heartbeatMs = HEARTBEAT_MS;
    }

    boolean send = !haveSent || moved >= SEND_MIN_METERS || now - lastSentAt >= heartbeatMs;
    if (!send) {
      stopGpsIfIdle();
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

            Log.i(TAG, "точку надіслано");
            updateNotification(notificationText(LocationManager.GPS_PROVIDER));
            stopGpsIfIdle();
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