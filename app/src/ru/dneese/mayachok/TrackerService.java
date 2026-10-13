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
import android.text.format.DateFormat;
import android.util.Log;

import java.util.Date;

/**
 * Трекер позиції. Надсилає точку кожні intervalSeconds — без умов.
 *
 * Тут навмисно немає жодної логіки «а може не надсилати». У попередніх
 * версіях її було 65 рядків: не надсилати, поки не набралося 100 метрів,
 * розтягувати інтервал до півгодини, вимикати GPS для економії, обирати
 * «кращу» точку за точністю. Кожне з тих рішень могло мовчки зупинити
 * надсилання — і в родині трапилося так двічі: сперва точка не
 * оновлювалася годину, потім телефон «замерз» на чужій точці в Перу на
 * чотири години, показуючи свіжі часові мітки.
 *
 * Тепер правило одне: фікса є — надсилаємо її. Батарея регулюється
 * вибором інтервалу в налаштуваннях, а не прихованою логікою, яку
 * не видно на екрані.
 *
 * Провайдери слухаються одночасно: GPS точний, але не працює в
 * приміщенні; мережевий приблизний, але працює всюди. Береться
 * найновіша фікса без жодних порівнянь.
 */
public final class TrackerService extends Service implements LocationListener {
  private static final String TAG = "GpsTracker";
  private static final String CHANNEL_ID = "gps_tracking";
  private static final int NOTIFICATION_ID = 42;

  // Фікса не може бути в майбутньому.
  //
  // Не абстракція: реально телефон віддав точку з часом на 514 днів уперед.
  // Старий код перевіряв лише застарілі точки, така пройшла, lastFixAt
  // став майбутнім — і далі всі справжні точки відкидалися як «не новіші».
  private static final long MAX_FIX_FUTURE_MS = 2 * 60 * 1000L;
  // Наскільки стара фікса ще придатна. Рахуємо від інтервалу, а не стало:
  // при інтервалі 15 хв стало 10 хв означало б, що власна ж фікса старіша за
  // половину інтервалу — і в приміщенні (де GPS довго не дає свіжої точки)
  // надсилати ставало б нічого. Беремо не менше 10 хв, щоб короткі інтервали
  // не надсилали зовсім стару точку зі свіжим часом.
  private static final long MIN_FIX_AGE_MS = 10 * 60 * 1000L;

  private long maxFixAgeMs = MIN_FIX_AGE_MS;

  private LocationManager locationManager;
  private HandlerThread workerThread;
  private Handler handler;
  private Api api;
  private Prefs prefs;

  private double lastLat;
  private double lastLon;
  private float lastAcc;
  private long lastFixAt;
  private boolean haveFix;
  private boolean inFlight;

  private String lastNotificationText = "";
  private long lastNotificationAt;

  @Override
  public void onCreate() {
    super.onCreate();
    api = new Api(this);
    prefs = new Prefs(this);
    locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    ensureChannel();
    // Якщо піднятися у фоні не вдалося (немає ACCESS_BACKGROUND_LOCATION на
    // Android 14+), startForeground кидає SecurityException. Без цієї обробки
    // процес падав би, а користувач бачив би лише «трекер не працює».
    if (!startInForeground(getString(R.string.status_searching))) {
      Diag.event(Diag.SERVICE_START, 0, 0, 0, "не вдалося стати foreground");
      prefs.setTracking(false);
      stopSelf();
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent == null ? null : intent.getAction();
    if ("stop".equals(action)) {
      prefs.setTracking(false);
      stopSelf();
      return START_NOT_STICKY;
    }

    if (!prefs.configured()) {
      Log.w(TAG, "не налаштовано: код групи порожній");
      prefs.setTracking(false);
      stopSelf();
      return START_NOT_STICKY;
    }

    // Сервіс — джерело правди про те, чи увімкнено трекінг. Це критично після
    // системного перезапуску: START_STICKY приходить із null-intent, і раніше
    // прапорець лишався false (його ставив лише екран). Наслідок був тихий:
    // після смерті процесу сервіс відновлювався, але наступний змах або
    // перезавантаження вже не піднімали його — трекер мовчав назавжди.
    prefs.setTracking(true);

    maxFixAgeMs = Math.max(MIN_FIX_AGE_MS, 2L * prefs.intervalSeconds() * 1000L);

    // Потоки створюємо один раз: повторний старт не повинен лишати старий
    // HandlerThread живим назавжди.
    if (workerThread == null) {
      workerThread = new HandlerThread("gps");
      workerThread.start();
      handler = new Handler(workerThread.getLooper());

      subscribe();
      sendNow();
    }
    return START_STICKY;
  }

  /**
   * Слухаємо GPS, мережу й пасивний провайдер одночасно.
   *
   * GPS дає точність метрів, але в приміщенні й метро не бачить жодного
   * супутника. Мережа дає сотні метрів, але працює всюди. Пасивний
   * провайдер не витрачає нічого — він просто підхоплює фікси, які система
   * вже рахує для інших застосунків.
   */
  private void subscribe() {
    long minTime = prefs.intervalSeconds() * 1000L;
    requestUpdates(LocationManager.GPS_PROVIDER, minTime);
    requestUpdates(LocationManager.NETWORK_PROVIDER, minTime);
    requestUpdates(LocationManager.PASSIVE_PROVIDER, minTime);
  }

  private void requestUpdates(String provider, long minTime) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
          && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
              != PackageManager.PERMISSION_GRANTED) {
        Log.w(TAG, "немає дозволу на геолокацію");
        stopSelf();
        return;
      }
      // minDistance = 0: система віддає фікса кожні minTime, як ми й просили.
      locationManager.requestLocationUpdates(provider, minTime, 0f, this, handler.getLooper());
    } catch (SecurityException error) {
      // Окремий провайдер може бути недоступний — решта працює далі.
      Log.w(TAG, "немає доступу до " + provider + ": " + error.getMessage());
    } catch (IllegalArgumentException error) {
      Log.w(TAG, "немає провайдера " + provider);
    }
  }

  @Override
  public void onLocationChanged(Location location) {
    if (location == null) return;

    long now = System.currentTimeMillis();
    long fixAt = location.getTime();

    if (fixAt > now + MAX_FIX_FUTURE_MS) {
      Diag.event(Diag.FIX_REJECTED, (fixAt - now) / 86400000.0d, 0, location.getAccuracy(),
          "час у майбутньому, провайдер=" + location.getProvider());
      return;
    }
    if (now - fixAt > maxFixAgeMs) {
      Diag.event(Diag.FIX_REJECTED, now - fixAt, 0, location.getAccuracy(),
          "застаріла, провайдер=" + location.getProvider());
      return;
    }

    // Беремо найновішу. Жодного порівняння за точністю: воно вже
    // «заморозило» одного з телефонів на чужій точці на години.
    if (haveFix && fixAt <= lastFixAt) return;

    lastLat = location.getLatitude();
    lastLon = location.getLongitude();
    lastAcc = location.hasAccuracy() ? location.getAccuracy() : 0f;
    lastFixAt = fixAt;
    haveFix = true;

    Diag.event(Diag.FIX_ACCEPTED, lastAcc, fixAt, 0,
        "провайдер=" + location.getProvider());
    updateNotification(notificationText(location.getProvider()));
  }

  @Override
  public void onProviderEnabled(String provider) {
    if (LocationManager.GPS_PROVIDER.equals(provider)) {
      updateNotification(getString(R.string.status_searching));
    }
  }

  @Override
  public void onProviderDisabled(String provider) {
    if (LocationManager.GPS_PROVIDER.equals(provider)) {
      updateNotification(getString(R.string.status_gps_off));
    }
  }

  @Override
  public void onStatusChanged(String provider, int status, Bundle extras) {}

  /** Надсилає поточну точку. Якщо фікси немає — просто чекаємо наступного тіку. */
  private void sendNow() {
    scheduleNext();
    if (!haveFix || inFlight) return;

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
              prefs.setLastSendError(error);
              Diag.event(Diag.SEND_FAIL, 0, Diag.hasNetwork() ? 1 : 0, 0, error);
              updateNotification(getString(R.string.status_send_failed));
              return;
            }
            prefs.setLastSendError("");
            prefs.setLastSentAt(System.currentTimeMillis());
            Diag.event(Diag.SEND_OK, acc, 0, 0, "успішно");
            updateNotification(notificationText(null));
          }
        });
  }

  private void scheduleNext() {
    if (handler == null) return;
    handler.removeCallbacksAndMessages(null);
    handler.postDelayed(
        new Runnable() {
          @Override
          public void run() {
            sendNow();
          }
        },
        prefs.intervalSeconds() * 1000L);
  }

  private String notificationText(String provider) {
    if (!haveFix) return getString(R.string.status_searching);
    String time = DateFormat.getTimeFormat(this).format(new Date(lastFixAt));
    String accuracy = lastAcc > 100f ? "±" + Math.round(lastAcc) + " м" : null;
    String source =
        LocationManager.NETWORK_PROVIDER.equals(provider)
            ? " · за мережею"
            : LocationManager.PASSIVE_PROVIDER.equals(provider) ? " · пасивна" : "";
    return time + (accuracy == null ? "" : " · " + accuracy) + source;
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

  /** Піднімає у фореграунд. Повертає false, якщо система не дозволила. */
  private boolean startInForeground(String text) {
    try {
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
      return true;
    } catch (RuntimeException error) {
      // SecurityException: немає ACCESS_BACKGROUND_LOCATION, а сервіс
      // піднімають із фону; ForegroundServiceStartNotAllowedException: фоновий
      // старт і взагалі заборонений. Ловимо обидва рядком вище.
      Log.w(TAG, "не вдалося стати foreground: " + error.getMessage());
      return false;
    }
  }

  /** Оновлюємо сповіщення не частіше разу на хвилину й лише коли текст змінився. */
  private synchronized void updateNotification(String text) {
    if (text.equals(lastNotificationText)) return;
    long now = android.os.SystemClock.elapsedRealtime();
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

  /**
   * Користувач змахнув застосунок із недавніх.
   *
   * На звичайному Android це інформаційний виклик: сервіс працює далі. Але
   * частина виробників (Samsung, Xiaomi, Huawei) ставиться до змаху як до
   * примусової зупинки — вбиває процес і викидає START_STICKY-перезапуск.
   * Тоді трекер мовчить назавжди, поки хтось не відкриє застосунок руками.
   *
   * Це не рішення про те, надсилати чи ні, а відновлення після вбивства,
   * тому воно лишається.
   */
  @Override
  public void onTaskRemoved(Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
    if (!prefs.isTracking()) return;
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

    Diag.event(Diag.TASK_REMOVED, 0, 0, 0, "ставлю будильник на підйом");

    Intent restart = new Intent(this, TrackerService.class);
    restart.setAction("start");
    PendingIntent pending =
        PendingIntent.getForegroundService(this, 7, restart, PendingIntent.FLAG_IMMUTABLE);

    android.app.AlarmManager alarms =
        (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
    if (alarms != null) {
      alarms.set(
          android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
          android.os.SystemClock.elapsedRealtime() + 10_000L,
          pending);
    }
  }

  @Override
  public void onDestroy() {
    if (handler != null) handler.removeCallbacksAndMessages(null);
    if (workerThread != null) workerThread.quitSafely();
    try {
      if (locationManager != null) locationManager.removeUpdates(this);
    } catch (Exception ignored) {
      // сервіс уже відсутній у системі
    }

    // Прапорець «трекінг» тут не скидаємо: його ставить сам сервіс у
    // onStartCommand. Якщо система зруйнувала сервіс, а не зупинила його,
    // START_STICKY чесно підніме його знову — і трекінг має лишитися
    // увімкненим. Скидати на onDestroy значило б брехати про стан.
    Diag.flush();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}