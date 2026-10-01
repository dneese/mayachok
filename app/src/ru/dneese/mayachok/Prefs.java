package ru.dneese.mayachok;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

/** Налаштування: код групи, ім'я, інтервал, адреса сервера. */
public final class Prefs {
  private static final String FILE = "gpstracker";
  private static final String DEFAULT_API = "https://mayachok.kikikiska.workers.dev";

  private final SharedPreferences sp;

  public Prefs(Context context) {
    sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
  }

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

  public String api() {
    String url = sp.getString("api", DEFAULT_API);
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  public void setApi(String url) {
    sp.edit().putString("api", url == null || url.isEmpty() ? DEFAULT_API : url.trim()).apply();
  }

  public int intervalSeconds() {
    return Integer.parseInt(sp.getString("interval", "30"));
  }

  public void setIntervalSeconds(int seconds) {
    sp.edit().putString("interval", String.valueOf(seconds)).apply();
  }

  public boolean configured() {
    return code().length() >= 8 && api().length() > 0;
  }

  public boolean isTracking() {
    return sp.getBoolean("tracking", false);
  }

  public void setTracking(boolean tracking) {
    sp.edit().putBoolean("tracking", tracking).apply();
  }
}
