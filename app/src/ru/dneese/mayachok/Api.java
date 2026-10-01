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

  /** Повертає код групи або null з описом помилки. */
  public interface GroupCallback {
    void onResult(String code, String error);
  }

  private static final class GroupResult {
    final String code;
    final String error;

    GroupResult(String code, String error) {
      this.code = code;
      this.error = error;
    }
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

  /**
   * Створює нову групу на сервері й повертає її код.
   * Код генерує сервер, тож формат лишається в одному місці.
   */
  public void createGroup(final GroupCallback callback) {
    pool.execute(
        new Runnable() {
          @Override
          public void run() {
            GroupResult result = doCreateGroup();
            callback.onResult(result.code, result.error);
          }
        });
  }

  private GroupResult doCreateGroup() {
    HttpURLConnection connection = null;
    try {
      Prefs prefs = new Prefs(context);
      URL url = new URL(prefs.api() + "/api/create");
      connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(10000);
      connection.setRequestProperty("Accept", "application/json");
      connection.setRequestProperty("Content-Length", "0");
      connection.getOutputStream().close();

      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) {
        return new GroupResult(null, "Сервер відповів помилкою (" + status + ")");
      }

      String body = read(connection.getInputStream());
      String code = extractJsonString(body, "code");
      if (code == null || code.isEmpty()) {
        return new GroupResult(null, "Сервер не повернув код");
      }
      return new GroupResult(code, null);

    } catch (Exception error) {
      return new GroupResult(null, "Немає зв’язку з сервером");
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  /** Мінімальний пошук рядкового поля у відповіді JSON. Без зовнішніх бібліотек. */
  private static String extractJsonString(String json, String key) {
    String needle = "\"" + key + "\"";
    int at = json.indexOf(needle);
    if (at < 0) return null;
    int colon = json.indexOf(':', at + needle.length());
    if (colon < 0) return null;
    int from = json.indexOf('"', colon + 1);
    if (from < 0) return null;
    int to = json.indexOf('"', from + 1);
    if (to < 0) return null;
    return json.substring(from + 1, to);
  }

  private static String read(InputStream stream) throws Exception {
    BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
    StringBuilder text = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) text.append(line);
    reader.close();
    return text.toString();
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
