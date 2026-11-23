package ru.dneese.mayachok;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Діагностика: що телефон насправді робив і чому не надсилав точки.
 *
 * Увімкнена вручну галочкою й за замовчуванням вимкнена. Ніколи не пише
 * координати — тільки події та причини. Живе на тому самому сервері, що й
 * точки, сторонніх сервісів немає, рядки видаляються через 48 годин.
 *
 * Події збираються в буфер і відправляються пачками: інакше ми б витрачали
 * D1-ліміт на рядки по одному разу на подію.
 */
public final class Diag {
  private static final String TAG = "MayachokDiag";

  // --- коди подій ---
  public static final int SERVICE_START = 1;   // сервіс піднято
  public static final int SERVICE_STOP = 2;    // сервіс зупинено
  public static final int STICKY_RESTART = 3;  // системний перезапуск
  public static final int TASK_REMOVED = 4;    // змахнули з недавніх
  public static final int SENSORS = 5;         // які датчики доступні
  public static final int SIGMOTION_ARMED = 6;
  public static final int SIGMOTION_FIRED = 7;
  public static final int SIGMOTION_REARM = 8; // обхід багу #259298110
  public static final int ACCEL_ON = 9;
  public static final int ACCEL_OFF = 10;
  public static final int MOTION_ON = 11;      // рух виявлено
  public static final int MOTION_OFF = 12;
  public static final int FIX_ACCEPTED = 13;
  public static final int FIX_REJECTED = 14;   // у s — причина
  public static final int GPS_ON = 15;
  public static final int GPS_OFF = 16;
  public static final int SEND_OK = 17;
  public static final int SEND_FAIL = 18;      // у s — помилка
  public static final int SEND_SKIP = 19;      // вирішили не надсилати
  public static final int SKIP_NO_FRESH_FIX = 20;
  public static final int SKIP_MOTION_PENDING = 21;
  public static final int STALE_RETRY = 22;
  public static final int BYPASS = 23;         // обхідний інтервал 15 хв
  public static final int ENV = 24;            // пристрій, Doze, батарея
  public static final int PERMISSION = 25;
  public static final int DRAINED = 26;        // буфер відправлено

  private static final int FLUSH_AT = 120;   // подій
  private static final long FLUSH_MS = 30 * 1000L;
  private static final int MAX_EVENTS = 3000; // стільки максимум за сесію

  private static final List<String> buffer = new ArrayList<String>();
  // Один потік: і періодичний злив, і надсилання пачок — по черзі.
  private static final ScheduledExecutorService io = Executors.newScheduledThreadPool(2);
  private static int dropped = 0;
  private static String session = "";
  private static Context appContext;
  private static Prefs prefs;
  private static boolean schedulerInstalled;
  private static boolean uploading;

  private Diag() {}

  /** Увімкнено користувачем? Усе інше — мовчить, якщо ні. */
  private static synchronized boolean enabled() {
    return prefs != null && prefs.diagnostics();
  }

  public static synchronized void attach(Context context) {
    if (appContext == null) {
      appContext = context.getApplicationContext();
      prefs = new Prefs(appContext);
      session = UUID.randomUUID().toString().substring(0, 8);
    }
    if (!enabled()) return;
    if (!schedulerInstalled) {
      schedulerInstalled = true;
      io.scheduleWithFixedDelay(
          new Runnable() {
            @Override
            public void run() {
              flush();
            }
          },
          FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS);
    }
    event(ENV, Build.VERSION.SDK_INT, batteryPercent(),
        ignoresBatteryOptimizations() ? 1 : 0,
        Build.MANUFACTURER + " " + Build.MODEL + " / Маячок " + prefs.appVersion());
  }

  /** Запис події. Дешево: якщо вимкнено — це один synchronized на порожньому буфері. */
  public static synchronized void event(int code, double a, double b, double c, String text) {
    if (!enabled() || appContext == null) return;
    if (buffer.size() >= MAX_EVENTS) {
      dropped++;
      return;
    }
    buffer.add(
        "{\"t\":" + System.currentTimeMillis()
            + ",\"e\":" + code
            + ",\"a\":" + a
            + ",\"b\":" + b
            + ",\"c\":" + c
            + ",\"s\":" + quote(text == null ? "" : text)
            + "}");
    if (buffer.size() >= FLUSH_AT) flush();
  }

  public static void event(int code, String text) {
    event(code, 0, 0, 0, text);
  }

  public static void event(int code, double a, String text) {
    event(code, a, 0, 0, text);
  }

  public static synchronized void flush() {
    if (!enabled() || buffer.isEmpty() || uploading) return;
    final List<String> batch = new ArrayList<String>(buffer);
    final int lost = dropped;
    dropped = 0;
    uploading = true;

    io.execute(
        new Runnable() {
          @Override
          public void run() {
            boolean sent = post(batch, lost);
            completeFlush(sent, batch.size());
          }
        });
  }

  private static synchronized void completeFlush(boolean sent, int count) {
    uploading = false;
    if (sent) {
      for (int i = 0; i < count && !buffer.isEmpty(); i++) buffer.remove(0);
    }
  }

  private static boolean post(List<String> batch, int lost) {
    HttpURLConnection connection = null;
    try {
      StringBuilder body = new StringBuilder();
      body.append("{\"code\":").append(quote(prefs.code()))
          .append(",\"uid\":").append(quote(prefs.uid()))
          .append(",\"session\":").append(quote(session))
          .append(",\"dropped\":").append(lost)
          .append(",\"events\":[");
      for (int i = 0; i < batch.size(); i++) {
        if (i > 0) body.append(',');
        body.append(batch.get(i));
      }
      body.append("]}");

      URL url = new URL(new Prefs(appContext).api() + "/api/diag");
      connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(5000);
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty("x-member-token", prefs.memberToken());
      OutputStream out = connection.getOutputStream();
      out.write(body.toString().getBytes("UTF-8"));
      out.close();
      int status = connection.getResponseCode();
      return status >= 200 && status < 300;
    } catch (Exception error) {
      // Діагностика не має ламати трекінг: мовчки йдемо далі.
      android.util.Log.w(TAG, "не вдалося надіслати діагностику: " + error.getMessage());
      return false;
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  // --- навколишнє середовище: те, що найчастіше ламає трекінг ---

  public static double batteryPercent() {
    try {
      BatteryManager manager =
          (BatteryManager) appContext.getSystemService(Context.BATTERY_SERVICE);
      int level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
      return level;
    } catch (Exception error) {
      return 0;
    }
  }

  /** Чи виключив користувач оптимізацію батареї для нас. */
  public static boolean ignoresBatteryOptimizations() {
    try {
      PowerManager manager = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
      return manager != null && manager.isIgnoringBatteryOptimizations(appContext.getPackageName());
    } catch (Exception error) {
      return false;
    }
  }

  /** Режим енергозбереження — у ньому Android різко гіршає надсиланню. */
  public static boolean powerSaveMode() {
    try {
      PowerManager manager = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
      return manager != null && manager.isPowerSaveMode();
    } catch (Exception error) {
      return false;
    }
  }

  /** Android 12+: чи дозволено застосунку прокидатися з фону. */
  public static boolean backgroundRestricted() {
    try {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
      ActivityManager manager = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
      return manager != null && manager.isBackgroundRestricted();
    } catch (Exception error) {
      return false;
    }
  }

  public static boolean hasNetwork() {
    try {
      ConnectivityManager manager =
          (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
      if (manager == null) return true;
      NetworkInfo info = manager.getActiveNetworkInfo();
      return info != null && info.isConnected();
    } catch (Exception error) {
      return true;
    }
  }

  /** Сторінки «налаштування оптимізації батареї» для нашого застосунку. */
  public static Intent batterySettingsIntent() {
    try {
      return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
    } catch (Exception error) {
      return new Intent(Settings.ACTION_SETTINGS);
    }
  }

  private static String quote(String value) {
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') out.append('\\').append(c);
      else if (c < 0x20) out.append(' ');
      else out.append(c);
    }
    return out.append('"').toString();
  }
}