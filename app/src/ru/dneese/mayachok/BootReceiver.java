package ru.dneese.mayachok;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Піднімає трекер після перезавантаження телефону.
 *
 * START_STICKY переживає вбитий процес, але НЕ переживає перезавантаження:
 * після нього сервіс мертвий доти, доки користувач сам не відкриє застосунок.
 * Саме це трапилося в родині — телефон перезавантажився (або ж упав так,
 * що Android перезапустив систему), і трекер мовчав годинами, хоча
 * застосунок був увімкнений.
 */
public final class BootReceiver extends BroadcastReceiver {
  private static final String TAG = "GpsTracker";
  // Константи ACTION_QUICKBOOT_POWERON у Android немає — це приватний рядок,
  // який надсилають Samsung та Xiaomi. Пишемо його вручну.
  private static final String QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON";

  @Override
  public void onReceive(Context context, Intent intent) {
    String action = intent == null ? null : intent.getAction();
    if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
        && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
        && !QUICKBOOT.equals(action)) {
      return;
    }

    Prefs prefs = new Prefs(context);
    // Діагностику приєднуємо одразу: після перезавантаження це єдина точка,
    // де ми взагалі можемо записати, що будильник спрацював. Інакше рішення
    // «підняти чи ні» лишається абсолютно невидимим для діагностики.
    Diag.attach(context);
    if (!prefs.configured() || !prefs.isTracking()) {
      Diag.event(Diag.SERVICE_START, 0, 0, 0, "boot: пропускаю — трекінг вимкнено");
      return;
    }

    Log.i(TAG, "запуск після перезавантаження: піднімаю трекер");
    Diag.event(Diag.SERVICE_START, 0, 0, 0, "boot: піднімаю трекер");

    Intent service = new Intent(context, TrackerService.class);
    service.setAction("start");
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
      else context.startService(service);
    } catch (Exception error) {
      // Android 12+ не дає піднімати foreground-сервіс із фону так просто.
      // Тоді не втрачаємо надсилання, а лише відкладаємо до відкриття.
      Log.w(TAG, "не вдалося підняти сервіс: " + error.getMessage());
      Diag.event(Diag.SERVICE_START, 0, 0, 0, "boot: старт не вдався: " + error.getMessage());
    }
  }
}