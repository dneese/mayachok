package ru.dneese.mayachok;

import android.content.Context;
import android.os.BatteryManager;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Надсилає одну точку на Worker. Усе йде у фоновому потоці. */
public final class Api {
  private final Context context;
  private final ExecutorService pool = Executors.newSingleThreadExecutor();

  public Api(Context context) {
    this.context = context.getApplicationContext();
  }

  /** Повертає null при успіху або текст помилки. */
  public interface Callback {
    void onResult(String error);
  }

  public void send(final double lat, final double lon, final float acc, final Callback callback) {
    final Prefs prefs = new Prefs(context);
    pool.execute(
        new Runnable() {
          @Override
          public void run() {
            callback.onResult(doSend(prefs, lat, lon, acc));
          }
        });
  }

  private String doSend(Prefs prefs, double lat, double lon, float acc) {
    HttpURLConnection connection = null;
    try {
      StringBuilder query = new StringBuilder();
      query.append("uid=").append(enc(prefs.uid()));
      query.append("&code=").append(enc(prefs.code()));
      query.append("&lat=").append(String.format(java.util.Locale.US, "%.6f", lat));
      query.append("&lon=").append(String.format(java.util.Locale.US, "%.6f", lon));
      query.append("&t=").append(System.currentTimeMillis());
      if (acc > 0) query.append("&acc=").append(Math.round(acc));
      if (!prefs.name().isEmpty()) query.append("&name=").append(enc(prefs.name()));
      Integer battery = batteryPercent();
      if (battery != null) query.append("&bat=").append(battery);

      URL url = new URL(prefs.api() + "/api/ingest?" + query);
      connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(10000);
      connection.setRequestProperty("Accept", "application/json");

      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) return "HTTP " + status;

      InputStream stream = connection.getInputStream();
      BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
      while (reader.readLine() != null) {
        // читаємо відповідь, щоб зʼєднання закрилося коректно
      }
      reader.close();
      return null;
    } catch (Exception error) {
      return error.getClass().getSimpleName() + ": " + error.getMessage();
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private Integer batteryPercent() {
    try {
      BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
      int level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
      return level > 0 ? level : null;
    } catch (Exception error) {
      return null;
    }
  }

  private static String enc(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8");
    } catch (Exception error) {
      return value;
    }
  }
}
