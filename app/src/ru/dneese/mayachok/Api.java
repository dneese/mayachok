package ru.dneese.mayachok;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Надсилає точки на Worker. Усе йде у фоновому потоці. */
public final class Api {
  private final Context context;
  // Окремі черги для точок і для всього іншого. Спільна черга означала, що
  // зависла чатова перевірка (а таймаут у неї 10 с) відсувала надсилання
  // точки — тобто карпалась стрілка на мапі через чат.
  private final ExecutorService pool = Executors.newSingleThreadExecutor();
  private final ExecutorService points = Executors.newSingleThreadExecutor();

  // Діагностика реальних телефонів показала, що 5 секунд замало: у слабкій
  // мережі (метро, село, стрибок між Wi-Fi і LTE) з'єднання не встигало
  // встановитися, і точка падала з SocketTimeoutException. Дали запас:
  // 10 с на з'єднання, 15 с на читання — краще зачекати, ніж втратити точку.
  private static final int CONNECT_TIMEOUT_MS = 10000;
  private static final int READ_TIMEOUT_MS = 15000;

  // Одна швидка повторна спроба після мережевої невдачі. Найчастіша причина
  // збою — тимчасовий обрив DNS (UnknownHostException), який за секунду
  // минає. Друга спроба повертає більшість таких точок.
  private static final int SEND_ATTEMPTS = 2;
  private static final long SEND_RETRY_DELAY_MS = 1200;

  public Api(Context context) {
    this.context = context.getApplicationContext();
  }

  /** Повертає null при успіху або текст помилки. */
  public interface Callback {
    void onResult(String error);
  }

  /** Реєстрація в групі: повертає токен учасника або текст помилки. */
  public interface JoinCallback {
    void onResult(String token, String error);
  }

  /**
   * Чиста перевірка мережі перед запитом.
   *
   * Навіщо окремо, якщо запит і так упаде: так ми не б'ємося в зачинені двері
   * й одразу кажемо «немає мережі», замість чекати на таймаут. Плюс це
   * дозволяє відрізнити «телефон без мережі» від «сервер недоступний».
   */
  public static boolean online(Context context) {
    try {
      ConnectivityManager manager =
          (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      if (manager == null) return true; // не знаємо — не блокуємо
      NetworkInfo info = manager.getActiveNetworkInfo();
      return info != null && info.isConnected();
    } catch (Exception error) {
      return true;
    }
  }

  /**
   * Вихід із групи на сервері. Без цього людина «вийшла б» лише у себе на
   * екрані, а її остання точка лишалася б у базі — усі в групі бачили б,
   * де вона була.
   */
  public void leave(final String code, final String token, final Callback callback) {
    pool.execute(
        new Runnable() {
          @Override
          public void run() {
            HttpURLConnection connection = null;
            try {
              String url =
                  new Prefs(context).api()
                      + "/api/leave?code="
                      + URLEncoder.encode(code, "UTF-8")
                      + "&uid="
                      + URLEncoder.encode(new Prefs(context).uid(), "UTF-8");
              connection = open(url);
              if (token != null && !token.isEmpty()) {
                connection.setRequestProperty("x-member-token", token);
              }
              callback.onResult(connection.getResponseCode() == 200 ? null : "не вийшло");
            } catch (Exception error) {
              callback.onResult("немає зв'язку");
            } finally {
              if (connection != null) connection.disconnect();
            }
          }
        });
  }

  public void join(final String code, final String name, final JoinCallback callback) {
    pool.execute(
        new Runnable() {
          @Override
          public void run() {
            JoinResult result = doJoin(code, name);
            callback.onResult(result.token, result.error);
          }
        });
  }

  private static final class JoinResult {
    final String token;
    final String error;

    JoinResult(String token, String error) {
      this.token = token;
      this.error = error;
    }
  }

  private JoinResult doJoin(String code, String name) {
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
      connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
      connection.setReadTimeout(READ_TIMEOUT_MS);
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty("Accept", "application/json");
      if (!prefs.memberToken().isEmpty()) {
        connection.setRequestProperty("x-member-token", prefs.memberToken());
      }
      connection.getOutputStream().write(body.getBytes("UTF-8"));
      connection.getOutputStream().close();

      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) {
        return new JoinResult(null, "Сервер відповів помилкою (" + status + ")");
      }
      String response = read(connection.getInputStream());
      String token = extractJsonString(response, "token");
      if (token == null && !prefs.memberToken().isEmpty()) token = prefs.memberToken();
      if (token == null || token.isEmpty()) {
        return new JoinResult(null, "Сервер не повернув токен учасника");
      }
      return new JoinResult(token, null);
    } catch (Exception error) {
      return new JoinResult(null, "Немає зв’язку з сервером");
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  /** GET із таймаутами — спільна точка для читання. */
  private static HttpURLConnection open(String fullUrl) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(fullUrl).openConnection();
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
    connection.setReadTimeout(READ_TIMEOUT_MS);
    connection.setRequestProperty("Accept", "application/json");
    return connection;
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

  /** Повертає код групи, токен учасника або null з описом помилки. */
  public interface GroupCallback {
    void onResult(String code, String token, String error);
  }

  private static final class GroupResult {
    final String code;
    final String token;
    final String error;

    GroupResult(String code, String token, String error) {
      this.code = code;
      this.token = token;
      this.error = error;
    }
  }

  /**
   * Надсилає одну точку.
   *
   * fixAt — час, коли точка була виміряна, а не коли ми вирішили її
   * відправити. Раніше туди йшов System.currentTimeMillis(), тож свіжа
   * точка могла прийти на сервер із часом на півгодини пізніше свого
   * справжнього вимірювання.
   */
  public void send(
      final double lat,
      final double lon,
      final float acc,
      final long fixAt,
      final Callback callback) {
    final Prefs prefs = new Prefs(context);
    points.execute(
        new Runnable() {
          @Override
          public void run() {
            // Дешева перевірка перед запитом: без неї ми щохвилини чекаємо
            // по 5 секунд таймауту там, де мережі немає взагалі.
            if (!online(context)) {
              callback.onResult("немає мережі");
              return;
            }
            callback.onResult(doSend(prefs, lat, lon, acc, fixAt));
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
            callback.onResult(result.code, result.token, result.error);
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
      connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
      connection.setReadTimeout(READ_TIMEOUT_MS);
      connection.setRequestProperty("Accept", "application/json");
      connection.setRequestProperty("Content-Type", "application/json");
      String requestBody = "{\"uid\":" + jsonString(prefs.uid()) + ",\"name\":" + jsonString(prefs.name()) + "}";
      connection.getOutputStream().write(requestBody.getBytes("UTF-8"));
      connection.getOutputStream().close();

      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) {
        return new GroupResult(null, null, "Сервер відповів помилкою (" + status + ")");
      }

      String response = read(connection.getInputStream());
      String code = extractJsonString(response, "code");
      String token = extractJsonString(response, "token");
      if (code == null || code.isEmpty()) {
        return new GroupResult(null, null, "Сервер не повернув код");
      }
      if (token == null || token.isEmpty()) {
        return new GroupResult(null, null, "Сервер не повернув токен учасника");
      }
      return new GroupResult(code, token, null);

    } catch (Exception error) {
      return new GroupResult(null, null, "Немає зв’язку з сервером");
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

  /** Результат однієї спроби: успіх, чи варто повторити, і текст помилки. */
  private static final class SendOutcome {
    final boolean success;
    final boolean retryable;
    final String error;

    SendOutcome(boolean success, boolean retryable, String error) {
      this.success = success;
      this.retryable = retryable;
      this.error = error;
    }
  }

  /**
   * Надсилає точку, за потреби — з однією повторною спробою.
   *
   * Повторюємо лише мережеві збої (немає з'єднання, DNS, таймаут), а не
   * відповіді сервера з кодом помилки: якщо сервер уже відповів 4xx/5xx,
   * друга спроба нічого не змінить.
   */
  private String doSend(Prefs prefs, double lat, double lon, float acc, long fixAt) {
    String lastError = null;
    for (int attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
      SendOutcome outcome = trySend(prefs, lat, lon, acc, fixAt);
      if (outcome.success) return null;
      lastError = outcome.error;
      if (!outcome.retryable) return lastError;
      if (attempt < SEND_ATTEMPTS) {
        try {
          Thread.sleep(SEND_RETRY_DELAY_MS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return lastError;
        }
      }
    }
    return lastError;
  }

  private SendOutcome trySend(Prefs prefs, double lat, double lon, float acc, long fixAt) {
    HttpURLConnection connection = null;
    try {
      String body =
          "{\"uid\":"
              + jsonString(prefs.uid())
              + ",\"code\":"
              + jsonString(prefs.code())
              + ",\"lat\":"
              + String.format(java.util.Locale.US, "%.6f", lat)
              + ",\"lon\":"
              + String.format(java.util.Locale.US, "%.6f", lon)
              + ",\"t\":"
              + (fixAt > 0 ? fixAt : System.currentTimeMillis())
              + ",\"acc\":"
              + (acc > 0 && acc < 9999f ? Math.round(acc) : 0)
              + ",\"name\":"
              + jsonString(prefs.name())
              + ",\"bat\":"
              + (batteryPercent() == null ? 0 : batteryPercent())
              + "}";

      URL url = new URL(prefs.api() + "/api/ingest");
      connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
      connection.setReadTimeout(READ_TIMEOUT_MS);
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty("Accept", "application/json");
      connection.setRequestProperty("x-member-token", prefs.memberToken());
      connection.getOutputStream().write(body.getBytes("UTF-8"));
      connection.getOutputStream().close();

      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) {
        return new SendOutcome(false, false, "HTTP " + status);
      }

      InputStream stream = connection.getInputStream();
      BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
      while (reader.readLine() != null) {
      }
      reader.close();
      return new SendOutcome(true, false, null);
    } catch (Exception error) {
      return new SendOutcome(
          false, true, error.getClass().getSimpleName() + ": " + error.getMessage());
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
