package ru.dneese.mayachok;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;
import java.util.UUID;

/**
 * Налаштування користувача. Адреси сервера, мапи й GitHub тут сталі —
 * користувач їх не змінює, тож і не бачить в інтерфейсі.
 */
public final class Prefs {
  private static final String FILE = "mayachok";

  private static final String API = "https://mayachok.kikikiska.workers.dev";
  private static final String MAP = "https://dneese.github.io/mayachok";
  private static final String GITHUB = "https://github.com/dneese/mayachok";
  private final SharedPreferences sp;

  public Prefs(Context context) {
    sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
  }

  /** Стабільний ідентифікатор цього телефону. Тому бачить себе на карті. */
  public String uid() {
    String uid = sp.getString("uid", null);
    if (uid == null) {
      uid = UUID.randomUUID().toString();
      sp.edit().putString("uid", uid).apply();
    }
    return uid;
  }

  public String code() {
    return sp.getString("code", "");
  }

  public void setCode(String code) {
    sp.edit().putString("code", code == null ? "" : code.trim()).apply();
  }

  public String name() {
    return sp.getString("name", "");
  }

  public void setName(String name) {
    sp.edit().putString("name", name == null ? "" : name.trim()).apply();
  }

  public int intervalSeconds() {
    try {
      return Integer.parseInt(sp.getString("interval", "60"));
    } catch (NumberFormatException error) {
      return 60;
    }
  }

  public void setIntervalSeconds(int seconds) {
    sp.edit().putString("interval", String.valueOf(seconds)).apply();
  }

  public boolean configured() {
    return code().length() >= 8;
  }

  public boolean isTracking() {
    return sp.getBoolean("tracking", false);
  }

  public void setTracking(boolean tracking) {
    sp.edit().putBoolean("tracking", tracking).apply();
  }

  /**
   * Чи показувати сповіщення про нові повідомлення, коли застосунок закритий.
   * За замовчуванням увімкнено: користувач сам вирішує, чи йому потрібен цей фон.
   */
  public boolean chatAlerts() {
    return sp.getBoolean("chat_alerts", true);
  }

  public void setChatAlerts(boolean enabled) {
    sp.edit().putBoolean("chat_alerts", enabled).apply();
  }

  /**
   * Водяна позначка чату: id останнього повідомлення, про яке користувач уже знає.
   * Спільна для екрана чату й фонового сервісу — тому вони не дублюють одне одному.
   */
  public long chatSeenId() {
    try {
      return Long.parseLong(sp.getString("chat_seen_id", "0"));
    } catch (NumberFormatException error) {
      return 0;
    }
  }

  public void setChatSeenId(long id) {
    sp.edit().putString("chat_seen_id", String.valueOf(id)).apply();
  }

  public String api() {
    return API;
  }

  /** Посилання на мапу з кодом у фрагменті: сайт GitHub коду не отримує. */
  public String mapUrl() {
    return MAP + "/#" + code();
  }

  public String githubUrl() {
    return GITHUB;
  }

  /**
   * Застосунок не дає прямого посилання на APK: користувач іде на сторінку
   * проєкту, де в README лежить посилання на останню збірку. Так ми ніколи
   * не роздаємо застарілі файли, навіть якщо хтось збере старе посилання.
   */

  /** Пряме посилання, яке відкриває застосунок (якщо його встановлено). */
  public String deepLink() {
    return "mayachok://join/" + prettyCode();
  }

  /**
   * Дістає код із того, що вставив користувач: посилання, deep link або сам код.
   * Люди копіюють посилання з месенджера, тому приймати саме його — зручніше,
   * ніж просити вирізати з нього останні символи.
   */
  public static String extractCode(String input) {
    if (input == null) return "";
    String text = input.trim();
    if (text.isEmpty()) return "";

    // mayachok://join/код — беремо лише останній сегмент шляху,
    // інакше в код просочилися б слова «mayachok» та «join».
    int slash = text.lastIndexOf('/');
    if (slash >= 0 && slash < text.length() - 1) text = text.substring(slash + 1);

    // код зберігається у фрагменті після '#' — беремо саме його
    int hash = text.lastIndexOf('#');
    if (hash >= 0 && hash < text.length() - 1) text = text.substring(hash + 1);

    return text.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.US);
  }

  /**
   * Текст запрошення: одразу готовий до відправлення в месенджер.
   * Користувач не знає, що таке «код», тому провідним є посилання,
   * а код — лише другим рядком, для того хто вводить вручну.
   */
  public String inviteText() {
    return String.format(
        Locale.US,
        "Маячок — сімейний GPS-трекер.%n%n"
            + "Відкрий ось це посилання — і ти в групі:%n%s%n%n"
            + "Код групи (якщо треба ввести вручну): %s%n"
            + "Застосунок для Android: %s",
        mapUrl(), prettyCode(), GITHUB);
  }

  /**
   * Приводить код до вигляду xxxx-xxxx-xxxx, щоб його легко прочитати вголос.
   * Групуємо завжди по чотири — інакше старий 24-символьний код показувався б
   * інакше, ніж на мапі, і люди помилялися б при переписуванні.
   */
  public String prettyCode() {
    String raw = extractCode(code());
    if (raw.length() <= 4) return raw;
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < raw.length(); i++) {
      if (i > 0 && i % 4 == 0) out.append('-');
      out.append(raw.charAt(i));
    }
    return out.toString();
  }
}
