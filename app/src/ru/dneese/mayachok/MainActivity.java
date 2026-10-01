package ru.dneese.mayachok;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Один екран і три вкладки: Група → Мапа → Чат.
 *
 * Вкладки перемикаються нижнім меню, а апаратна кнопка «назад» завжди
 * повертає на Групу. Ключова деталь: назад НІКОЛИ не віддається WebView, бо
 * той інакше повертається на about:blank — це давало білий екран.
 */
public final class MainActivity extends Activity {
  private static final int REQ_LOCATION = 10;
  private static final int REQ_NOTIFICATIONS = 11;
  private static final int REQ_CHAT_ALERTS = 12;
  // Інтервал опитування GPS. 10 секунд прибрано: з порогом 100 м воно
  // лише тримало GPS-модуль увімкненим майже постійно, не додаючи точності.
  private static final int[] INTERVALS = {30, 60, 120, 300};

  /** ChatService ставить цей прапорець: відкритися одразу на чаті. */
  public static final String EXTRA_OPEN_CHAT = "open_chat";

  /** Вкладки. */
  private static final int TAB_GROUP = 0;
  private static final int TAB_MAP = 1;
  private static final int TAB_CHAT = 2;

  private static final int[] TITLES = {
    R.string.app_name, R.string.tab_map, R.string.tab_chat,
  };

  private Prefs prefs;
  private Api api;

  private TextView status;
  private View statusDot;
  private TextView title;
  private ImageButton back;
  private EditText nameInput;

  private View groupEmpty;
  private View groupReady;
  private TextView codeValue;
  private EditText codeInput;
  private Button createButton;
  private Button joinButton;

  private Spinner intervalSpinner;
  private Button toggle;
  private CheckBox chatAlertsBox;

  private View mainPanel;
  private LinearLayout mapPanel;
  private View chatPanel;
  private View[] tabs;
  private TextView[] tabTexts;
  private WebView mapView;
  private View mapError;
  private ScrollView chatLog;
  private LinearLayout chatList;
  private TextView chatEmpty;
  private EditText chatInput;

  private int currentTab = TAB_GROUP;
  private boolean mapLoaded; // сторінку мапи вже відкривали
  private boolean mapFailed;
  private long lastMessageId;
  private boolean chatStarted; // чат уже підписаний на оновлення

  // Групування повідомлень у бульбашки: поки той самий відправник і не минуло
  // GROUP_WINDOW_MS, наступне повідомлення продовжує попереднє.
  private static final long GROUP_WINDOW_MS = 5 * 60 * 1000L;
  private String lastRunSender;
  private long lastRunAt;
  private int lastRenderedDay = -1; // для роздільника дат; -1 — ще не було

  private final Handler ticker = new Handler(Looper.getMainLooper());

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    prefs = new Prefs(this);
    api = new Api(this);

    status = findViewById(R.id.status);
    statusDot = findViewById(R.id.status_dot);
    title = findViewById(R.id.title);
    back = findViewById(R.id.back);
    nameInput = findViewById(R.id.name);

    groupEmpty = findViewById(R.id.group_empty);
    groupReady = findViewById(R.id.group_ready);
    codeValue = findViewById(R.id.code_value);
    codeInput = findViewById(R.id.code);
    createButton = findViewById(R.id.create);
    joinButton = findViewById(R.id.join);

    intervalSpinner = findViewById(R.id.interval);
    toggle = findViewById(R.id.toggle);

    mainPanel = findViewById(R.id.main_panel);
    mapPanel = findViewById(R.id.map_panel);
    chatPanel = findViewById(R.id.chat_panel);
    mapView = findViewById(R.id.map_view);
    chatLog = findViewById(R.id.chat_log);
    chatList = findViewById(R.id.chat_list);
    chatEmpty = findViewById(R.id.chat_empty);
    chatInput = findViewById(R.id.chat_input);
    chatAlertsBox = findViewById(R.id.chat_alerts);

    tabs = new View[] {findViewById(R.id.tab_group), findViewById(R.id.tab_map),
        findViewById(R.id.tab_chat)};
    tabTexts = new TextView[] {findViewById(R.id.tab_group_text), findViewById(R.id.tab_map_text),
        findViewById(R.id.tab_chat_text)};

    nameInput.setText(prefs.name());
    codeInput.setText(prefs.code());

    setUpInterval();
    setUpTabs();
    setUpActions();
    setUpMap();

    handleDeepLink(getIntent());
    showTab(TAB_GROUP);
    render();
  }

  private void setUpInterval() {
    String[] labels = new String[INTERVALS.length];
    for (int i = 0; i < INTERVALS.length; i++) {
      labels[i] = i == 0 ? INTERVALS[i] + " с (часто)" : INTERVALS[i] + " с";
    }
    intervalSpinner.setAdapter(
        new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
            labels));
    for (int i = 0; i < INTERVALS.length; i++) {
      if (INTERVALS[i] == prefs.intervalSeconds()) {
        intervalSpinner.setSelection(i);
        break;
      }
    }
  }

  private void setUpTabs() {
    for (int i = 0; i < tabs.length; i++) {
      final int index = i;
      tabs[i].setOnClickListener(
          new View.OnClickListener() {
            @Override
            public void onClick(View view) {
              if (index == TAB_MAP && !prefs.configured()) {
                toast("Спершу створіть групу");
                return;
              }
              if (index == TAB_CHAT && !prefs.configured()) {
                toast(getString(R.string.chat_no_group));
                return;
              }
              showTab(index);
            }
          });
    }
  }

  private void setUpActions() {
    createButton.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            createGroup();
          }
        });

    joinButton.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            joinGroup();
          }
        });

    findViewById(R.id.share)
        .setOnClickListener(
            new View.OnClickListener() {
              @Override
              public void onClick(View view) {
                shareInvite();
              }
            });

    findViewById(R.id.copy)
        .setOnClickListener(
            new View.OnClickListener() {
              @Override
              public void onClick(View view) {
                copy(prefs.mapUrl());
              }
            });

    findViewById(R.id.map)
        .setOnClickListener(
            new View.OnClickListener() {
              @Override
              public void onClick(View view) {
                showTab(TAB_MAP);
              }
            });

    findViewById(R.id.chat)
        .setOnClickListener(
            new View.OnClickListener() {
              @Override
              public void onClick(View view) {
                showTab(TAB_CHAT);
              }
            });

    findViewById(R.id.forget)
        .setOnClickListener(
            new View.OnClickListener() {
              @Override
              public void onClick(View view) {
                confirmForget();
              }
            });

    findViewById(R.id.github)
        .setOnClickListener(
            new View.OnClickListener() {
              @Override
              public void onClick(View view) {
                openUrl(prefs.githubUrl());
              }
            });

    toggle.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            saveBasics();
            if (isTracking()) stopTracking();
            else startTracking();
          }
        });

    back.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            goBack();
          }
        });

    chatAlertsBox.setOnCheckedChangeListener(
        new CompoundButton.OnCheckedChangeListener() {
          @Override
          public void onCheckedChanged(CompoundButton button, boolean checked) {
            // render() виставляє стан програмно, і ми не хочемо на кожне
            // перемальовування екрана запускати й зупиняти сервіс: тому
            // реагуємо лише на тап, а не на будь-яку зміну.
            if (button.isPressed()) onChatAlertsToggled(checked);
          }
        });

    findViewById(R.id.chat_send)
        .setOnClickListener(
            new View.OnClickListener() {
              @Override
              public void onClick(View view) {
                sendChat();
              }
            });
  }

  /**
   * WebView без залежностей: та сама сторінка, що й у браузері. Помилки
   * показуємо своїм екраном, а не порожнім білим полотном.
   */
  private void setUpMap() {
    WebSettings settings = mapView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    // карта не має ані реклами, ані зовнішніх переходів — зовнішнім
    // посиланням не довіряємо, щоб не виводило нас із застосунку
    settings.setSupportMultipleWindows(false);
    settings.setMediaPlaybackRequiresUserGesture(true);

    mapView.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            // карта має лишатися на dneese.github.io, решта — у браузер
            if (uri != null && "dneese.github.io".equals(uri.getHost())) return false;
            openUrl(uri == null ? "" : uri.toString());
            return true;
          }

          @Override
          public void onPageFinished(WebView view, String url) {
            mapFailed = false;
            mapError.setVisibility(View.GONE);
            mapView.setVisibility(View.VISIBLE);
          }

          @Override
          public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            // цікавить лише головна сторінка: поломка тайла не має ховати карту
            if (request == null || !request.isForMainFrame()) return;
            showMapError();
          }

          /**
           * Стара сигнатура — для Android 5.0–6.0 (API 21–22), де нового
           * onReceivedError з WebResourceRequest ще немає. Без неї на цих
           * пристроях помилка завантаження просто не мала б шансу показатись.
           */
          @Override
          @SuppressWarnings("deprecation")
          public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            showMapError();
          }
        });

    // помилка + кнопка повтору
    LinearLayout errorBox = new LinearLayout(this);
    errorBox.setOrientation(LinearLayout.VERTICAL);
    errorBox.setGravity(Gravity.CENTER);
    errorBox.setPadding(dp(28), dp(24), dp(28), dp(24));
    errorBox.setVisibility(View.GONE);
    // та сама вага, що в карти: при помилці вона займає весь простір і
    // текст стає по центру, а не прилипає до верху під app bar
    LinearLayout.LayoutParams errorParams =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
    errorBox.setLayoutParams(errorParams);

    TextView errorText = new TextView(this);
    errorText.setText(R.string.map_error);
    errorText.setTextColor(Color.parseColor("#111827"));
    errorText.setTextSize(17);
    errorText.setGravity(Gravity.CENTER);
    errorBox.addView(errorText);

    TextView errorHint = new TextView(this);
    errorHint.setText(R.string.map_hint);
    errorHint.setTextColor(Color.parseColor("#6B7280"));
    errorHint.setTextSize(14);
    errorHint.setGravity(Gravity.CENTER);
    errorHint.setPadding(0, dp(10), 0, dp(18));
    errorBox.addView(errorHint);

    Button retry = new Button(this);
    retry.setText(R.string.map_retry);
    retry.setTextColor(Color.WHITE);
    retry.setTextSize(15);
    retry.setBackgroundResource(R.drawable.bg_btn_primary);
    retry.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            loadMap(true);
          }
        });
    errorBox.addView(retry);

    // помилка лежить поверх карти всередині map_panel
    mapPanel.addView(errorBox, 1);
    mapError = errorBox;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  /** Показує вкладку й оновлює заголовок та стан кнопки «назад». */
  private void showTab(int tab) {
    if (tab != TAB_GROUP && !prefs.configured()) return;

    currentTab = tab;
    mainPanel.setVisibility(tab == TAB_GROUP ? View.VISIBLE : View.GONE);
    mapPanel.setVisibility(tab == TAB_MAP ? View.VISIBLE : View.GONE);
    chatPanel.setVisibility(tab == TAB_CHAT ? View.VISIBLE : View.GONE);

    for (int i = 0; i < tabs.length; i++) {
      boolean active = i == tab;
      tabs[i].setSelected(active);
      tabTexts[i].setTextColor(active ? Color.WHITE : Color.parseColor("#111827"));
    }

    title.setText(TITLES[tab]);
    // На Групі «назад» виходить із застосунку, решта — повертає на Групу.
    back.setVisibility(tab == TAB_GROUP ? View.INVISIBLE : View.VISIBLE);
    updateTabAvailability();

    if (tab == TAB_MAP) {
      if (!mapLoaded) loadMap(false);
      // карта має знову зайняти весь екран після повернення з іншої вкладки
      mapView.post(
          new Runnable() {
            @Override
            public void run() {
              mapView.invalidate();
            }
          });
    } else if (tab == TAB_CHAT) {
      startChat();
    } else {
      stopChat();
    }

    // Найважливіше для батареї. Сторінка мапи опитує API таймером раз на
    // 15 секунд, і setInterval у JavaScript НЕ зупиняється, коли вкладка
    // просто прихована. Без pauseTimers() ми вдень робимо ~26 тисяч запитів
    // і тримаємо CPU та радіо ввімкненими дарма.
    if (tab == TAB_MAP) mapView.resumeTimers();
    else mapView.pauseTimers();
  }

  /**
   * Без групи мапа й чат порожні, тож позначаємо їх тьмяними: видно, що
   * вони є, але ще не готові. Активна вкладка лишається яскравою.
   */
  private void updateTabAvailability() {
    boolean ready = prefs.configured();
    for (int i = 1; i < tabs.length; i++) {
      boolean active = i == currentTab;
      float alpha = ready || active ? 1f : 0.35f;
      tabs[i].setAlpha(alpha);
      tabTexts[i].setAlpha(alpha);
    }
  }

  private void goBack() {
    if (currentTab != TAB_GROUP) {
      showTab(TAB_GROUP);
      return;
    }
    super.onBackPressed();
  }

  @Override
  public void onBackPressed() {
    // Клавіатура забирає назад першою — інакше не закриється.
    View focused = getCurrentFocus();
    if (focused != null && focused.getId() == R.id.chat_input) {
      chatInput.clearFocus();
      return;
    }
    if (currentTab != TAB_GROUP) {
      showTab(TAB_GROUP);
      return;
    }
    super.onBackPressed();
  }

  private void loadMap(boolean force) {
    if (!prefs.configured()) return;
    if (mapLoaded && !force && !mapFailed) return;

    mapError.setVisibility(View.GONE);
    mapView.setVisibility(View.VISIBLE);
    // заповнюємо фон кольором карти, щоб не було білого спалаху
    mapView.setBackgroundColor(Color.parseColor("#E8EAED"));
    mapView.loadUrl(prefs.mapUrl());
    mapLoaded = true;
  }

  // --- чат ---

  private void startChat() {
    if (chatStarted) {
      pullChat();
      ticker.postDelayed(chatTick, 4000);
      return;
    }
    chatStarted = true;
    lastMessageId = 0;
    chatList.removeAllViews();
    // скидаємо стан групування, інакше перше повідомлення після
    // повторного відкриття продовжить групу з попередньої сесії
    lastRenderedDay = -1;
    lastRunSender = null;
    lastRunAt = 0;
    chatEmpty.setVisibility(View.VISIBLE);
    pullChat();
    ticker.postDelayed(chatTick, 4000);
  }

  private void stopChat() {
    // чат не має робити запити у фоні — інакше вони з’їдають батарею
    ticker.removeCallbacks(chatTick);
  }

  private final Runnable chatTick =
      new Runnable() {
        @Override
        public void run() {
          if (currentTab != TAB_CHAT) return;
          pullChat();
          ticker.postDelayed(this, 4000);
        }
      };

  private void pullChat() {
    if (currentTab != TAB_CHAT || !prefs.configured()) return;
    final long after = lastMessageId;
    api.chat(
        prefs.code(),
        after,
        new Api.ChatCallback() {
          @Override
          public void onResult(final List<Api.Message> messages, String error) {
            runOnUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    if (messages == null || messages.isEmpty()) return;
                    for (Api.Message message : messages) {
                      // опитування могло налетіти двічі — не дублюємо
                      if (message.id <= lastMessageId) continue;
                      lastMessageId = message.id;
                      addMessageRow(message);
                    }
                    // Показане на екрані повідомлення вже «прочитане»:
                    // рухаємо спільну позначку, щоб ChatService не повідомив
                    // про нього ще раз, поки ми просто дивилися в чат.
                    prefs.setChatSeenId(lastMessageId);
                    if (chatList.getChildCount() > 0) {
                      chatEmpty.setVisibility(View.GONE);
                      chatLog.post(
                          new Runnable() {
                            @Override
                            public void run() {
                              chatLog.fullScroll(ScrollView.FOCUS_DOWN);
                            }
                          });
                    }
                  }
                });
          }
        });
  }

  /** Повідомлення у стилі Telegram: бульбашка з хвостиком, аватар, час у кінці. */
  private void addMessageRow(Api.Message message) {
    boolean mine = message.uid != null && message.uid.equals(prefs.uid());
    String name = (message.name == null || message.name.trim().isEmpty()) ? "Хтось" : message.name.trim();

    // Роздільник дат — новий день у стрічці чату.
    Calendar day = Calendar.getInstance();
    day.setTimeInMillis(message.ts * 1000L);
    int dayKey = day.get(Calendar.YEAR) * 1000 + day.get(Calendar.DAY_OF_YEAR);
    if (dayKey != lastRenderedDay) {
      lastRenderedDay = dayKey;
      chatList.addView(daySeparator(message.ts));
    }

    // Повідомлення того самого відправника за останні 5 хвилин — одна група:
    // аватар і ім'я показуємо лише в першому, як у Telegram.
    boolean sameRun =
        lastRunSender != null
            && lastRunSender.equals(mine ? "\u0000self" : message.uid)
            && message.ts - lastRunAt <= GROUP_WINDOW_MS
            && lastRunAt <= message.ts;
    if (!sameRun) {
      lastRunSender = mine ? "\u0000self" : message.uid;
      lastRunAt = message.ts;
    }
    boolean firstInRun = !sameRun;

    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(mine ? Gravity.END : Gravity.START);
    row.setPadding(0, firstInRun ? dp(8) : dp(1), 0, dp(1));

    // Аватар лише для чужих повідомлень; для своїх — порожнє місце,
    // щоб текст не «стрибав» убік між повідомленнями різних авторів.
    View avatar = avatarView(name, mine ? "" : firstLetter(name));
    row.addView(avatar);

    LinearLayout bubble = new LinearLayout(this);
    bubble.setOrientation(LinearLayout.VERTICAL);
    bubble.setBackgroundResource(
        mine
            ? (firstInRun ? R.drawable.bg_bubble_out : R.drawable.bg_bubble_out_notail)
            : (firstInRun ? R.drawable.bg_bubble_in : R.drawable.bg_bubble_in_notail));

    int padH = dp(12);
    int padTop = dp(7);
    // низ більший, щоб час не стояв упритук до краю бульбашка
    int padBottom = dp(6);

    if (!mine && firstInRun) {
      TextView sender = new TextView(this);
      sender.setText(name);
      sender.setTextSize(13);
      sender.setTypeface(null, android.graphics.Typeface.BOLD);
      sender.setTextColor(accentFor(message.uid));
      sender.setPadding(padH, padTop - dp(2), padH, 0);
      bubble.addView(sender);
    }

    // Текст і час в одному рядку: час притискається донизу праворуч,
    // як у Telegram, а не висить окремим рядком над повідомленням.
    LinearLayout line = new LinearLayout(this);
    line.setOrientation(LinearLayout.HORIZONTAL);
    line.setGravity(Gravity.BOTTOM);
    line.setPadding(padH, mine ? padTop : 0, padH, padBottom);

    TextView body = new TextView(this);
    body.setText(message.body);
    body.setTextSize(16);
    body.setTextColor(Color.parseColor(mine ? "#FFFFFF" : "#111827"));
    // maxWidth живе в TextView, а не в LayoutParams: без нього довге
    // повідомлення розтягнуло б бульбашок на всю ширину екрана.
    body.setMaxWidth(bubbleMaxWidth());
    line.addView(body, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

    TextView meta = new TextView(this);
    meta.setText(timeLabel(message, mine));
    meta.setTextSize(11);
    meta.setTextColor(Color.parseColor(mine ? "#C7DBFF" : "#8A94A6"));
    meta.setPadding(dp(8), 0, 0, 0);
    line.addView(
        meta,
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

    bubble.addView(line);

    // Бульбашка не має розтягуватися на всю ширину — у Telegram
    // довгі рядки переносяться, короткі залишаються вузькими.
    row.addView(bubble, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

    chatList.addView(row);
  }

  /** Максимальна ширина бульбашка: 78% екрана мінус аватар і поля. */
  private int bubbleMaxWidth() {
    return (int) (getResources().getDisplayMetrics().widthPixels * 0.78f);
  }

  /** Показує блок помилки замість карти й фіксує, що сторінка не завантажилась. */
  private void showMapError() {
    mapFailed = true;
    mapView.setVisibility(View.GONE);
    mapError.setVisibility(View.VISIBLE);
  }

  /** Час у бульбашку; для своїх — з галочкою, як «доставлено». */
  private String timeLabel(Api.Message message, boolean mine) {
    String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(message.ts));
    return mine ? time + "  \u2713\u2713" : time;
  }

  /** Роздільник дат: сіра «таблетка» по центру, як у Telegram. */
  private View daySeparator(long ts) {
    TextView chip = new TextView(this);
    Calendar then = Calendar.getInstance();
    then.setTimeInMillis(ts * 1000L);
    Calendar now = Calendar.getInstance();

    long dayDiff = now.getTimeInMillis() - then.getTimeInMillis();
    int todayDiff = (int) Math.round(dayDiff / 86400000.0d);
    String label;
    if (todayDiff == 0) {
      label = "Сьогодні";
    } else if (todayDiff == 1) {
      label = "Вчора";
    } else {
      label = new SimpleDateFormat("d MMMM", Locale.getDefault()).format(new Date(ts));
    }

    chip.setText(label);
    chip.setTextSize(12);
    chip.setTextColor(Color.parseColor("#3D4657"));
    chip.setBackgroundResource(R.drawable.bg_date_chip);
    chip.setGravity(Gravity.CENTER);
    chip.setPadding(dp(14), dp(4), dp(14), dp(4));

    LinearLayout wrap = new LinearLayout(this);
    wrap.setOrientation(LinearLayout.HORIZONTAL);
    wrap.setGravity(Gravity.CENTER);
    wrap.setPadding(0, dp(10), 0, dp(10));
    wrap.addView(
        chip,
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    return wrap;
  }

  private static String firstLetter(String name) {
    if (name == null || name.isEmpty()) return "?";
    return name.substring(0, 1).toUpperCase(Locale.getDefault());
  }

  /** Круглий аватар: для своїх повідомлень порожній, щоб ширина рядка не змінювалась. */
  private View avatarView(String name, String letter) {
    TextView avatar = new TextView(this);
    avatar.setText(letter);
    avatar.setTextSize(15);
    avatar.setTypeface(null, android.graphics.Typeface.BOLD);
    avatar.setGravity(Gravity.CENTER);
    avatar.setTextColor(Color.WHITE);
    avatar.setBackgroundResource(R.drawable.bg_avatar);
    avatar.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accentFor(name)));
    if (letter.isEmpty()) avatar.setVisibility(View.INVISIBLE);
    int size = dp(36);
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(size, size);
    params.setMargins(0, dp(2), dp(8), 0);
    avatar.setLayoutParams(params);
    return avatar;
  }

  /** Колір аватара й імені — стабільний для одного uid, як у Telegram. */
  private int accentFor(String seed) {
    int[] palette = {
      Color.parseColor("#E17076"),
      Color.parseColor("#7BC862"),
      Color.parseColor("#65AADD"),
      Color.parseColor("#A695E7"),
      Color.parseColor("#EE7AAE"),
      Color.parseColor("#6EC9CB"),
      Color.parseColor("#F2A65A"),
    };
    int hash = 0;
    String text = seed == null ? "" : seed;
    for (int i = 0; i < text.length(); i++) hash = hash * 31 + text.charAt(i);
    return palette[Math.abs(hash) % palette.length];
  }

  private void sendChat() {
    if (!prefs.configured()) return;
    final String text = chatInput.getText().toString().trim();
    if (text.isEmpty()) return;
    chatInput.setText("");

    api.say(
        prefs.code(),
        text,
        new Api.SayCallback() {
          @Override
          public void onResult(String error) {
            runOnUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    if (error == null) {
                      pullChat();
                    } else {
                      chatInput.setText(text); // не даємо загубити написане
                      toast(error);
                    }
                  }
                });
          }
        });
  }

  // --- життєвий цикл ---

  @Override
  protected void onResume() {
    super.onResume();
    render();
    if (currentTab == TAB_CHAT) startChat();
    // відновлюємо таймери мапи лише якщо вона на екрані — інакше вони
    // пішли б буркотіти у фоні
    if (currentTab == TAB_MAP) mapView.resumeTimers();
  }

  @Override
  protected void onPause() {
    super.onPause();
    stopChat();
    // Застосунок у фоні або екран вимкнено — таймери мапи зупиняються,
    // інакше вона ганяла б запити в кишені всю ніч.
    mapView.pauseTimers();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleDeepLink(intent);
    // тап по сповіщенню про повідомлення має відкрити саме чат
    if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_CHAT, false)) {
      intent.removeExtra(EXTRA_OPEN_CHAT);
      showTab(TAB_CHAT);
    }
    render();
  }

  /** mayachok://join/xxxx-xxxx-xxxx — запрошення відкриває застосунок. */
  private void handleDeepLink(Intent intent) {
    if (intent == null || intent.getData() == null) return;
    Uri data = intent.getData();
    if (!"mayachok".equals(data.getScheme())) return;
    String code = Prefs.extractCode(data.toString());
    if (!isCode(code)) return;
    prefs.setCode(code);
    if (!codeInput.getText().toString().isEmpty()) codeInput.setText(code);
  }

  // --- група ---

  private void saveBasics() {
    prefs.setName(nameInput.getText().toString());
    prefs.setIntervalSeconds(INTERVALS[intervalSpinner.getSelectedItemPosition()]);
  }

  private void createGroup() {
    saveBasics();
    createButton.setEnabled(false);
    createButton.setText("Створюю…");

    api.createGroup(
        new Api.GroupCallback() {
          @Override
          public void onResult(final String code, final String error) {
            runOnUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    createButton.setEnabled(true);
                    createButton.setText(R.string.btn_create_group);
                    if (code != null) {
                      prefs.setCode(code);
                      // група змінилась — мапу треба перезавантажити з новим кодом
                      mapLoaded = false;
                      toast("Групу створено");
                      if (!prefs.isTracking()) startTracking();
                      syncChatWatcher();
                    } else {
                      toast(error);
                    }
                    render();
                  }
                });
          }
        });
  }

  private static boolean isCode(String value) {
    return value != null && value.length() >= 8 && value.length() <= 64;
  }

  /** Приєднання за посиланням: код дістаємо з нього самі. */
  private void joinGroup() {
    String code = Prefs.extractCode(codeInput.getText().toString());
    if (!isCode(code)) {
      toast("Вставте посилання групи");
      return;
    }
    String name = nameInput.getText().toString().trim();
    if (name.isEmpty()) {
      toast("Введіть своє ім’я");
      return;
    }

    prefs.setCode(code);
    prefs.setName(name);
    prefs.setIntervalSeconds(INTERVALS[intervalSpinner.getSelectedItemPosition()]);
    mapLoaded = false;
    render();
    syncChatWatcher();

    joinButton.setEnabled(false);
    api.join(
        code,
        name,
        new Api.JoinCallback() {
          @Override
          public void onResult(final String error) {
            runOnUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    joinButton.setEnabled(true);
                    if (error != null) {
                      toast(error);
                      return;
                    }
                    toast("Ви в групі");
                    if (!prefs.isTracking()) startTracking();
                    syncChatWatcher();
                  }
                });
          }
        });
  }

  private void confirmForget() {
    new android.app.AlertDialog.Builder(this)
        .setTitle(R.string.btn_forget)
        .setMessage(R.string.forget_confirm)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(
            R.string.btn_forget,
            new android.content.DialogInterface.OnClickListener() {
              @Override
              public void onClick(android.content.DialogInterface dialog, int which) {
                stopTracking();
                stopChatWatcher();
                // Спочатку просимо сервер стерти людину: інакше вона лишилася
                // б на мапі в усіх, хто в тій групі. Код зберігаємо у змінній,
                // бо нижче його вже скинуто.
                final String leavingCode = prefs.code();
                if (isCode(leavingCode)) {
                  api.leave(
                      leavingCode,
                      new Api.Callback() {
                        @Override
                        public void onResult(String error) {
                          // Помилка не заважає: локально групу ми вже забули,
                          // а стару точку прибере щоденне прибирання бази.
                          if (error != null) toast("Не вдалося стерти слід у групі");
                        }
                      });
                }
                prefs.setCode("");
                // нова водяна позначка: у новій групі старі id не мають значення
                prefs.setChatSeenId(0);
                codeInput.setText("");
                mapLoaded = false;
                chatStarted = false;
                showTab(TAB_GROUP);
                render();
              }
            })
        .show();
  }

  /** Показує або ховає блоки залежно від того, чи є вже група. */
  private void render() {
    boolean hasGroup = prefs.configured();

    groupEmpty.setVisibility(hasGroup ? View.GONE : View.VISIBLE);
    groupReady.setVisibility(hasGroup ? View.VISIBLE : View.GONE);

    if (hasGroup) {
      codeValue.setText(prefs.mapUrl());
      if (codeInput.getText().toString().isEmpty()) codeInput.setText(prefs.code());
    }
    updateTabAvailability();

    // Ставимо стан програмно: слухач перемикача реагує лише на тап, тож
    // render() не перезапускає сервіс на кожному перемальовуванні екрана.
    chatAlertsBox.setChecked(prefs.chatAlerts());
    chatAlertsBox.setEnabled(hasGroup);
    chatAlertsBox.setAlpha(hasGroup ? 1f : 0.4f);

    boolean tracking = isTracking();
    toggle.setText(tracking ? R.string.btn_stop : R.string.btn_start);

    int dotColor;
    if (tracking) {
      status.setText(R.string.status_on);
      dotColor = Color.parseColor("#16A34A");
    } else if (hasGroup) {
      status.setText(R.string.status_ready);
      dotColor = Color.parseColor("#D97706");
    } else {
      status.setText(R.string.status_no_group);
      dotColor = Color.parseColor("#9CA3AF");
    }
    statusDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(dotColor));
  }

  private void shareInvite() {
    Intent send = new Intent(Intent.ACTION_SEND);
    send.setType("text/plain");
    send.putExtra(Intent.EXTRA_SUBJECT, "Маячок — група родичі");
    send.putExtra(Intent.EXTRA_TEXT, prefs.inviteText());
    try {
      startActivity(Intent.createChooser(send, getString(R.string.btn_share)));
    } catch (ActivityNotFoundException error) {
      copy(prefs.inviteText());
    }
  }

  private void copy(String text) {
    try {
      android.content.ClipboardManager board =
          (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
      board.setPrimaryClip(android.content.ClipData.newPlainText("mayachok", text));
      toast("Скопійовано");
    } catch (Exception error) {
      toast("Не вдалося скопіювати");
    }
  }

  private void openUrl(String url) {
    if (url.isEmpty()) return;
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (ActivityNotFoundException error) {
      toast("Немає програми, щоб відкрити посилання");
    }
  }

  private boolean isTracking() {
    return prefs.isTracking();
  }

  /** На API < 23 дозволи даються під час встановлення. */
  private boolean hasPermission(String permission) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
    return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
  }

  private void startTracking() {
    if (!prefs.configured()) {
      toast("Спершу створіть групу або введіть код");
      return;
    }
    if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
      requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
      return;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
      return;
    }

    Intent service = new Intent(this, TrackerService.class);
    service.setAction("start");
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
    else startService(service);

    prefs.setTracking(true);
    render();
    // дозвіл на сповіщення щойно міг бути виданий разом із трекером —
    // тож користуваємося нагодою запустити й стеження за чатом
    syncChatWatcher();
  }

  private void stopTracking() {
    Intent service = new Intent(this, TrackerService.class);
    service.setAction("stop");
    stopService(service);

    prefs.setTracking(false);
    render();
  }

  /**
   * Фонове стеження за чатом. Запускається, щойно є група, і лише якщо
   * користувач не вимкнув перемикач — без дозволу на сповіщення Android
   * просто не покаже нічого, тож не мучимо його зайвим сервісом.
   */
  private void syncChatWatcher() {
    if (!prefs.configured() || !prefs.chatAlerts()) {
      stopChatWatcher();
      return;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
      return;
    }
    Intent service = new Intent(this, ChatService.class);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
    else startService(service);
  }

  private void stopChatWatcher() {
    stopService(new Intent(this, ChatService.class));
  }

  /** Перемикач «сповіщати про повідомлення» на екрані групи. */
  private void onChatAlertsToggled(boolean enabled) {
    prefs.setChatAlerts(enabled);
    if (!enabled) {
      stopChatWatcher();
      return;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
      requestPermissions(
          new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_CHAT_ALERTS);
      return;
    }
    syncChatWatcher();
  }

  private void toast(String text) {
    if (text == null) return;
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (requestCode == REQ_LOCATION) {
      if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
        startTracking();
      } else {
        toast("Без геолокації трекер не працює");
      }
    } else if (requestCode == REQ_NOTIFICATIONS) {
      if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startTracking();
    } else if (requestCode == REQ_CHAT_ALERTS) {
      // перемикач увімкнено, але Android питав дозволу: без нього
      // повідомлення не показуються — тож запускаємо сервіс лише після згоди
      if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
        prefs.setChatAlerts(true);
        chatAlertsBox.setChecked(true);
        syncChatWatcher();
      } else {
        prefs.setChatAlerts(false);
        chatAlertsBox.setChecked(false);
        toast("Без дозволу сповіщення не працюватимуть");
      }
    }
  }
}
