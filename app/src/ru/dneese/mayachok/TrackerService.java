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
import android.util.Log;

/**
 * Foreground service: слухає GPS і раз на інтервал надсилає координати.
 * Працює з вимкненим екраном — саме тому це foreground service.
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

  private LocationManager locationManager;
  private HandlerThread handlerThread;
  private Handler handler;
  private Api api;
  private Prefs prefs;

  private double lastLat;
  private double lastLon;
  private float lastAcc;
  private boolean haveFix;

  // Остання надіслана точка — щоб не ганяти запити, коли ми стоїмо на місці.
  private boolean haveSent;
  private double lastSentLat;
  private double lastSentLon;
  private long lastSentAt;
  private long heartbeatMs = HEARTBEAT_MS;
  private int consecutiveFailures;

  @Override
  public void onCreate() {
    super.onCreate();
    api = new Api(this);
    prefs = new Prefs(this);
    locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
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

    handlerThread = new HandlerThread("gps");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());

    requestUpdates();
    scheduleNextSend(0);
    return START_STICKY;
  }

  private void requestUpdates() {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
          && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
              != PackageManager.PERMISSION_GRANTED) {
        Log.w(TAG, "немає дозволу на геолокацію");
        stopSelf();
        return;
      }

      // minTime дорівнює інтервалу: інакше GPS смикається без пауз і
      // ми надсилали б точку на кожному оновленні, ігноруючи вибір користувача.
      long minTime = prefs.intervalSeconds() * 1000L;
      locationManager.requestLocationUpdates(
          LocationManager.GPS_PROVIDER, minTime, 0f, this, handlerThread.getLooper());

      // Остання відома позиція — щоб не чекати холодного старту.
      Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
      if (last != null) onLocationChanged(last);

    } catch (SecurityException error) {
      Log.w(TAG, "відмовлено в доступі: " + error.getMessage());
      stopSelf();
    } catch (Exception error) {
      Log.w(TAG, "помилка GPS: " + error.getMessage());
      stopSelf();
    }
  }

  @Override
  public void onLocationChanged(Location location) {
    lastLat = location.getLatitude();
    lastLon = location.getLongitude();
    lastAcc = location.getAccuracy();
    haveFix = true;
    updateNotification("Остання точка: " + location.getTime() / 1000);
    // Надсилає не подія, а таймер у sendNow(): інакше разом із таймером
    // отримали б дві точки за інтервал. Таймер сам бере найсвіжішу позицію.
  }

  @Override
  public void onProviderEnabled(String provider) {
    if (LocationManager.GPS_PROVIDER.equals(provider)) {
      updateNotification("GPS увімкнено, чекаю сигнал…");
    }
  }

  @Override
  public void onProviderDisabled(String provider) {
    updateNotification("GPS вимкнено");
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
   * йде, ми не ганяємо запити в порожнечу. На сервер іде одна й та сама
   * остання точка, тож нічого не втрачається.
   */
  private void sendNow() {
    if (!haveFix) return;

    long now = System.currentTimeMillis();
    double moved = haveSent ? metersFrom(lastSentLat, lastSentLon) : Double.MAX_VALUE;

    // Лежить нерухомо — розтягуємо «подих» аж до півгодини. Це найбільша
    // економія: телефон на столі пише 48 разів на добу замість 288.
    if (haveSent && moved < STILL_METERS) {
      heartbeatMs = Math.min(heartbeatMs * 2, HEARTBEAT_MAX_MS);
    } else {
      heartbeatMs = HEARTBEAT_MS;
    }

    boolean send = !haveSent || moved >= SEND_MIN_METERS || now - lastSentAt >= heartbeatMs;
    if (!send) return;

    lastSentAt = now;
    lastSentLat = lastLat;
    lastSentLon = lastLon;
    haveSent = true;

    api.send(lastLat, lastLon, lastAcc,
        new Api.Callback() {
          @Override
          public void onResult(String error) {
            if (error != null) {
              consecutiveFailures++;
              Log.w(TAG, "надсилання не вдалося: " + error);
            } else {
              if (consecutiveFailures > 0) Log.i(TAG, "мережа ожила");
              consecutiveFailures = 0;
              Log.i(TAG, "точку надіслано");
            }
          }
        });
  }

  /**
   * Наступна спроба. Коли надсилання не вдалося — відходимо все далі
   * (від 2 до 8 інтервалів), щоб не бити в стіну, коли мережа лежить.
   * Вдалі — повертаємося до звичайного інтервалу.
   */
  private void scheduleNextSend(final long delayMs) {
    if (handler == null) return;
    handler.removeCallbacksAndMessages(null);
    handler.postDelayed(
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

  private void startInForeground(String text) {
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

  private void updateNotification(String text) {
    NotificationManager manager =
        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
  }

  private Notification buildNotification(String text) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationManager manager =
          (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
      NotificationChannel channel =
          new NotificationChannel(
              CHANNEL_ID, "Трекінг GPS", NotificationManager.IMPORTANCE_LOW);
      channel.setShowBadge(false);
      channel.setDescription("Показує, що трекер активний");
      if (manager != null) manager.createNotificationChannel(channel);
    }

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
    if (handlerThread != null) {
      if (handler != null) handler.removeCallbacksAndMessages(null);
      try {
        locationManager.removeUpdates(this);
      } catch (Exception ignored) {
        // сервіс уже відсутній у системі
      }
      handlerThread.quitSafely();
    }
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
