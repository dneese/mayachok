package ru.dneese.mayachok;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service чату: стежить за новими повідомленнями, поки застосунок
 * закритий, і показує сповіщення зі звуком.
 *
 * Навіщо сервіс, а не простий таймер у Activity: опитування в onPause зупиняється,
 * тому без сервіса повідомлення, написані поки телефон у кишені, не надійшли б узагалі.
 *
 * Два канали навмисно розділені:
 *  - chat_watch  — тихе «сервіс дивиться», importance LOW;
 *  - chat_message — саме сповіщення про повідомлення, importance HIGH і звук.
 * Якщо змішати їх в один канал, або постійне сповіщення почне гукати,
 * або чай приглушить Alerts. Android бере звук із каналу при створенні.
 */
public final class ChatService extends Service {
  private static final String TAG = "ChatWatch";
  private static final String CHANNEL_WATCH = "chat_watch";
  private static final String CHANNEL_ALERT = "chat_message";
  private static final int NOTIFY_WATCH = 43;
  private static final int NOTIFY_ALERT = 44;

  /**
   * Інтервал у фоні навмисно довший за 4 секунди на екрані: у фоні важлива
   * батарея, а повідомлення від родичі не термінові на секунди.
   */
  private static final long POLL_MS = 20000L;

  private Api api;
  private Prefs prefs;
  private HandlerThread thread;
  private Handler handler;

  @Override
  public void onCreate() {
    super.onCreate();
    api = new Api(this);
    prefs = new Prefs(this);
    thread = new HandlerThread("chat");
    thread.start();
    handler = new Handler(thread.getLooper());
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent != null ? intent.getAction() : null;
    if ("stop".equals(action)) {
      stopSelf();
      return START_NOT_STICKY;
    }

    if (!prefs.configured() || !prefs.chatAlerts()) {
      Log.i(TAG, "немає групи або сповіщення вимкнені — зупиняюсь");
      stopSelf();
      return START_NOT_STICKY;
    }

    startWatchForeground();
    poll();
    return START_STICKY;
  }

  /** Опитування чату й сповіщення про нові повідомлення. */
  private void poll() {
    if (handler == null) return;
    if (!prefs.configured() || !prefs.chatAlerts()) {
      stopSelf();
      return;
    }

    api.chat(
        prefs.code(),
        prefs.chatSeenId(),
        new Api.ChatCallback() {
          @Override
          public void onResult(List<Api.Message> messages, String error) {
            if (error == null && messages != null && !messages.isEmpty()) {
              handleMessages(messages);
            }
            reschedule();
          }
        });
  }

  private void handleMessages(List<Api.Message> messages) {
    // Екран чату міг уже показати ці повідомлення, поки ми чекали відповіді:
    // перечитуємо позначку, щоб не поставити те саме сповіщення вдруге.
    long seen = prefs.chatSeenId();
    String myUid = prefs.uid();

    List<Api.Message> fresh = new ArrayList<Api.Message>();
    long newest = seen;
    for (Api.Message message : messages) {
      if (message.id > newest) newest = message.id;
      if (message.id <= seen) continue;
      // свої повідомлення не сповіщаємо — їх і так щойно бачив користувач
      if (message.uid != null && message.uid.equals(myUid)) continue;
      fresh.add(message);
    }

    // Позначку рухаємо завжди, навіть якщо всі нові повідомлення свої:
    // інакше сервіс щоразу перезапитував би ті самі рядки.
    prefs.setChatSeenId(newest);

    if (fresh.isEmpty()) return;
    notifyMessages(fresh);
  }

  private void notifyMessages(List<Api.Message> fresh) {
    NotificationManager manager =
        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) return;

    ensureAlertChannel(manager);

    Notification.Builder builder =
        new Notification.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_send)
            .setAutoCancel(true)
            .setContentIntent(openChatIntent())
            .setCategory(Notification.CATEGORY_MESSAGE);

    if (fresh.size() == 1) {
      Api.Message message = fresh.get(0);
      builder.setContentTitle(displayName(message));
      builder.setContentText(message.body);
      builder.setStyle(
          new Notification.BigTextStyle().bigText(message.body));
    } else {
      builder.setContentTitle(
          fresh.size() + " " + pluralMessages(fresh.size()));
      builder.setContentText(lastOf(fresh));
      builder.setNumber(fresh.size());

      Notification.InboxStyle inbox = new Notification.InboxStyle();
      int shown = 0;
      for (Api.Message message : fresh) {
        if (shown++ >= 5) break;
        inbox.addLine(displayName(message) + ": " + message.body);
      }
      builder.setStyle(inbox);
    }

    // Один id на весь канал: нове повідомлення замінює попереднє, тож у шухляді
    // не накопичується стос «Маячок: нове повідомлення».
    manager.notify(NOTIFY_ALERT, builder.build());
  }

  private static String lastOf(List<Api.Message> fresh) {
    return fresh.get(fresh.size() - 1).body;
  }


  /** «повідомлення» / «повідомлення» / «повідомлень» — українська множина. */
  private static String pluralMessages(int n) {
    int mod100 = n % 100;
    if (mod100 >= 11 && mod100 <= 14) return "повідомлень";
    switch (n % 10) {
      case 1:
        return "повідомлення";
      case 2:
      case 3:
      case 4:
        return "повідомлення";
      default:
        return "повідомлень";
    }
  }

  private static String displayName(Api.Message message) {
    return (message.name == null || message.name.trim().isEmpty()) ? "Хтось" : message.name.trim();
  }

  private PendingIntent openChatIntent() {
    Intent open = new Intent(this, MainActivity.class);
    open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    open.putExtra(MainActivity.EXTRA_OPEN_CHAT, true);
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
    return PendingIntent.getActivity(this, 1, open, flags);
  }

  private void reschedule() {
    if (handler == null) return;
    handler.removeCallbacks(pollTask);
    handler.postDelayed(pollTask, POLL_MS);
  }

  private final Runnable pollTask =
      new Runnable() {
        @Override
        public void run() {
          poll();
        }
      };

  /** Тихе постійне сповіщення: воно і тримає сервіс живим у фоні. */
  private void startWatchForeground() {
    NotificationManager manager =
        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager != null) ensureWatchChannel(manager);

    Intent open = new Intent(this, MainActivity.class);
    open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
    PendingIntent pending = PendingIntent.getActivity(this, 0, open, flags);

    Notification notification =
        new Notification.Builder(this, CHANNEL_WATCH)
            .setContentTitle("Чат групи активний")
            .setContentText("Сповіщуватиму про нові повідомлення")
            .setSmallIcon(R.drawable.ic_send)
            .setContentIntent(pending)
            .setOngoing(true)
            .setShowWhen(false)
            .build();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
          NOTIFY_WATCH,
          notification,
          android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    } else {
      startForeground(NOTIFY_WATCH, notification);
    }
  }

  private void ensureWatchChannel(NotificationManager manager) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
    NotificationChannel channel =
        new NotificationChannel(CHANNEL_WATCH, "Чат групи", NotificationManager.IMPORTANCE_LOW);
    channel.setShowBadge(false);
    channel.setDescription("Показує, що застосунок стежить за чатом");
    // тиша: це технічне сповіщення про себе
    channel.setSound(null, null);
    channel.enableVibration(false);
    manager.createNotificationChannel(channel);
  }

  private void ensureAlertChannel(NotificationManager manager) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
    NotificationChannel channel =
        new NotificationChannel(
            CHANNEL_ALERT, "Нові повідомлення", NotificationManager.IMPORTANCE_HIGH);
    channel.setDescription("Сповіщення про повідомлення в чаті групи");
    channel.enableVibration(true);
    // Явно задаємо системний звук: навіть якщо користувач вимкнув звуки
    // для застосунків за замовчуванням, канал із явним звуком лишається дзвінким.
    Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    AudioAttributes attributes =
        new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build();
    channel.setSound(sound, attributes);
    manager.createNotificationChannel(channel);
  }

  @Override
  public void onDestroy() {
    if (handler != null) handler.removeCallbacksAndMessages(null);
    if (thread != null) thread.quitSafely();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}