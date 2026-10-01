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

  private LocationManager locationManager;
  private HandlerThread handlerThread;
  private Handler handler;
  private Api api;
  private Prefs prefs;

  private double lastLat;
  private double lastLon;
  private boolean haveFix;

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

      locationManager.requestLocationUpdates(
          LocationManager.GPS_PROVIDER, 0L, 0f, this, handlerThread.getLooper());

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
    haveFix = true;
    updateNotification("Остання точка: " + location.getTime() / 1000);
    // Надсилаємо одразу, не чекаючи таймера, щоб мапа реагувала швидко.
    sendNow();
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

  private void sendNow() {
    if (!haveFix) return;
    api.send(lastLat, lastLon, 0f,
        new Api.Callback() {
          @Override
          public void onResult(String error) {
            if (error != null) {
              Log.w(TAG, "надсилання не вдалося: " + error);
            } else {
              Log.i(TAG, "точку надіслано");
            }
          }
        });
  }

  private void scheduleNextSend(final long delayMs) {
    if (handler == null) return;
    handler.removeCallbacksAndMessages(null);
    handler.postDelayed(
        new Runnable() {
          @Override
          public void run() {
            sendNow();
            scheduleNextSend(prefs.intervalSeconds() * 1000L);
          }
        },
        delayMs);
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
