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

  /** Реєстрація в групі: повертає null при успіху або текст помилки. */
  public interface JoinCallback {
    void onResult(String error);
  }

  /** Одне повідомлення чату. */
  public static final class Message {
    public final long id;
    public final String uid;
    public final String name;
    public final String body;
    public final long ts;

    Message(long id, String uid, String name, String body, long ts) {
      this.id = id;
      this.uid = uid;
      this.name = name;
      this.body = body;
      this.ts = ts;
    }
  }

  /** Усі нові повідомлення після afterId (0 — усі). */
  public interface ChatCallback {
    void onResult(java.util.List<Message> messages, String error);
  }

  public interface SayCallback {
    void onResult(String error);
  }

  /**
   * Читає чат групи. Запит без фільтра повертає лише те, що новіше за afterId,
   * тож миттєвий стан «прочитано все» вкладається у одне число.
   */
  public void chat(final String code, final long afterId, final ChatCallback callback) {
    pool.execute(
        new Runnable() {
          @Override
          public void run() {
            HttpURLConnection connection = null;
            try {
              String url =
                  new Prefs(context).api()
                      + "/api/chat?code="
                      + URLEncoder.encode(code, "UTF-8")
                      + "&after="
                      + afterId;
              connection = open(url);
              if (connection.getResponseCode() != 200) {
                callback.onResult(null, "чат недоступний");
                return;
              }
                callback.onResult(parseChat(read(connection.getInputStream())), null);
            } catch (Exception error) {
              callback.onResult(null, "немає зв’язку");
            } finally {
              if (connection != null) connection.disconnect();
            }
          }
        });
  }

  /** Надсилає одне повідомлення від імені цього телефону. */
  public void say(final String code, final String body, final SayCallback callback) {
    pool.execute(
        new Runnable() {
          @Override
          public void run() {
            HttpURLConnection connection = null;
            try {
              Prefs prefs = new Prefs(context);
              String payload =
                  "{\"code\":"
                      + jsonString(code)
                      + ",\"uid\":"
                      + jsonString(prefs.uid())
                      + ",\"name\":"
                      + jsonString(prefs.name())
                      + ",\"body\":"
                      + jsonString(body)
                      + "}";
              connection = openJson("/api/say", payload);
              int status = connection.getResponseCode();
              if (status == 200) {
                callback.onResult(null);
              } else {
                callback.onResult(parseError(connection) + " (чат)");
              }
            } catch (Exception error) {
              callback.onResult("не вдалося надіслати");
            } finally {
              if (connection != null) connection.disconnect();
            }
          }
        });
  }

  /** Мінімальний розбір JSON масиву messages — без сторонніх бібліотек. */
  private static java.util.List<Message> parseChat(String json) {
    java.util.List<Message> out = new java.util.ArrayList<Message>();
    if (json == null) return out;
    int at = json.indexOf("\"messages\"");
    if (at < 0) return out;
    int from = json.indexOf('[', at);
    int to = json.lastIndexOf(']');
    if (from < 0 || to <= from) return out;

    int i = from + 1;
    while (i < to) {
      int objStart = json.indexOf('{', i);
      if (objStart < 0 || objStart > to) break;
      int objEnd = json.indexOf('}', objStart);
      if (objEnd < 0) break;
      String item = json.substring(objStart, objEnd + 1);
      out.add(
          new Message(
              longField(item, "id"),
              textField(item, "uid"),
              textField(item, "name"),
              textField(item, "body"),
              longField(item, "ts")));
      i = objEnd + 1;
    }
    return out;
  }

  private static long longField(String json, String key) {
    String marker = "\"" + key + "\":";
    int at = json.indexOf(marker);
    if (at < 0) return 0;
    int i = at + marker.length();
    int end = i;
    while (end < json.length() && "0123456789-".indexOf(json.charAt(end)) >= 0) end++;
    try {
      return end > i ? Long.parseLong(json.substring(i, end)) : 0;
    } catch (NumberFormatException error) {
      return 0;
    }
  }

  /** Читає рядкове поле, знімаючи лапки та екрановані символи. */
  private static String textField(String json, String key) {
    String marker = "\"" + key + "\":\"";
    int at = json.indexOf(marker);
    if (at < 0) return "";
    int i = at + marker.length();
    StringBuilder out = new StringBuilder();
    while (i < json.length()) {
      char c = json.charAt(i);
      if (c == '\\' && i + 1 < json.length()) {
        char next = json.charAt(i + 1);
        if (next == 'n') out.append('\n');
        else if (next == 't') out.append('\t');
        else if (next == 'r') out.append('\r');
        else if (next == 'u' && i + 5 < json.length()) {
          try {
            out.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
          } catch (NumberFormatException error) {
            out.append(next);
          }
          i += 6;
          continue;
        } else out.append(next);
        i += 2;
        continue;
      }
      if (c == '"') break;
      out.append(c);
      i++;
    }
    return out.toString();
  }

  /**
   * Повідомляє серверу, що цей телефон тепер у групі.
   * Роботи це до першої GPS-точки: людина одразу з'являється на мапі
   * як «чекаємо сигнал», а не лише через півхвилини.
   */
  public void join(final String code, final String name, final JoinCallback callback) {
    pool.execute(
        new Runnable() {
          @Override
          public void run() {
            callback.onResult(doJoin(code, name));
          }
        });
  }

  private String doJoin(String code, String name) {
    HttpURLConnection connection = null;
    try {
      Prefs prefs = new Prefs(context);
      String body =
          "{\"code\":"
              + jsonString(code)
              + ",\"uid\":"
              + jsonString(prefs.uid())
              + ",\"name\":"
              + jsonString(name)
              + "}";

      URL url = new URL(prefs.api() + "/api/join");
      connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(10000);
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty("Accept", "application/json");
      connection.getOutputStream().write(body.getBytes("UTF-8"));
      connection.getOutputStream().close();

      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) return "Сервер відповів помилкою (" + status + ")";
      connection.getInputStream().close();
      return null;
    } catch (Exception error) {
      return "Немає зв’язку з сервером";
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  /** GET із таймаутами — спільна точка для читання. */
  private static HttpURLConnection open(String fullUrl) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(fullUrl).openConnection();
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(10000);
    connection.setReadTimeout(10000);
    connection.setRequestProperty("Accept", "application/json");
    return connection;
  }

  /** POST із JSON-тілом. */
  private HttpURLConnection openJson(String path, String body) throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection) new URL(new Prefs(context).api() + path).openConnection();
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setConnectTimeout(10000);
    connection.setReadTimeout(10000);
    connection.setRequestProperty("Content-Type", "application/json");
    connection.setRequestProperty("Accept", "application/json");
    connection.getOutputStream().write(body.getBytes("UTF-8"));
    connection.getOutputStream().close();
    return connection;
  }

  /** Текст помилки з відповіді сервера, якщо його вдалося прочитати. */
  private static String parseError(HttpURLConnection connection) {
    try {
      java.io.InputStream stream = connection.getErrorStream();
      if (stream == null) return "Сервер відповів помилкою";
      return textField(read(stream), "error");
    } catch (Exception error) {
      return "Сервер відповів помилкою";
    }
  }

  private static String jsonString(String value) {
    if (value == null) return "\"\"";
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') out.append('\\').append(c);
      else if (c < 0x20) out.append('\\').append('u').append(String.format("%04x", (int) c));
      else out.append(c);
    }
    return out.append('"').toString();
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
