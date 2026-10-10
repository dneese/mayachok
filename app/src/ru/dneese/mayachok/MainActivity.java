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
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

/**
 * Один екран і дві вкладки: Група → Мапа.
 *
 * Вкладки перемикаються нижнім меню, а апаратна кнопка «назад» завжди
 * повертає на Групу. Ключова деталь: назад НІКОЛЕЙ не віддається WebView, бо
 * той інакше повертається на about:blank — це давало білий екран.
 */
public final class MainActivity extends Activity {
  private static final int REQ_LOCATION = 10;
  // Інтервал опитування GPS. 10 секунд прибрано: з порогом 100 м воно
  // лише тримало GPS-модуль увімкненим майже постійно, не додаючи точності.
  private static final int[] INTERVALS = {30, 60, 120, 300};

  /** Вкладки. */
  private static final int TAB_GROUP = 0;
  private static final int TAB_MAP = 1;

  private static final int[] TITLES = {R.string.app_name, R.string.tab_map};

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

  private View mainPanel;
  private LinearLayout mapPanel;
  private View[] tabs;
  private TextView[] tabTexts;
  private WebView mapView;
  private View mapError;
  private boolean mapLoaded; // сторінку мапи вже відкривали
  private boolean mapFailed;

  private int currentTab = TAB_GROUP;

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
    mapView = findViewById(R.id.map_view);

    tabs = new View[] {findViewById(R.id.tab_group), findViewById(R.id.tab_map)};
    tabTexts =
        new TextView[] {findViewById(R.id.tab_group_text), findViewById(R.id.tab_map_text)};

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
    }

    // Найважливіше для батареї. Сторінка мапи опитує API таймером раз на
    // 15 секунд, і setInterval у JavaScript НЕ зупиняється, коли вкладка
    // просто прихована. Без pauseTimers() ми вдень робимо ~26 тисяч запитів
    // і тримаємо CPU та радіо ввімкненими дарма. onPause() додатково
    // зупиняє таймери всередині WebView (JAVA/SCRIPT) і анімації.
    if (tab == TAB_MAP) {
      mapView.resumeTimers();
      mapView.onResume();
    } else {
      mapView.pauseTimers();
      mapView.onPause();
      // Не вантажимо далі тайли й не тримаємо з'єднання, поки мапа не на екрані.
      mapView.stopLoading();
    }
  }

  /**
   * Без групи мапа порожня, тож позначаємо її тьмяною: видно, що вона є,
   * але ще не готова. Активна вкладка лишається яскравою.
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

  /** Показує блок помилки замість карти й фіксує, що сторінка не завантажилась. */
  private void showMapError() {
    mapFailed = true;
    mapView.setVisibility(View.GONE);
    mapError.setVisibility(View.VISIBLE);
  }

  // --- життєвий цикл ---

  @Override
  protected void onResume() {
    super.onResume();
    render();
    // відновлюємо таймери мапи лише якщо вона на екрані — інакше вони
    // пішли б буркотіти у фоні
    if (currentTab == TAB_MAP) {
      mapView.resumeTimers();
      mapView.onResume();
    }
    // Рядок статусу має показувати «остання точка: N хв тому» чесно, тож
    // перечитуємо його раз на півхвилини, поки екран відкритий.
    ticker.postDelayed(statusTick, 30 * 1000L);
  }

  @Override
  protected void onPause() {
    super.onPause();
    ticker.removeCallbacks(statusTick);
    // Застосунок у фоні або екран вимкнено — таймери мапи зупиняються,
    // інакше вона ганяла б запити в кишені всю ніч.
    mapView.pauseTimers();
    mapView.onPause();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleDeepLink(intent);
    render();
  }

  private final Runnable statusTick =
      new Runnable() {
        @Override
        public void run() {
          render();
          ticker.postDelayed(this, 30 * 1000L);
        }
      };

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
                          // Обов'язково через runOnUiThread: callback приходить
                          // з фонового потоку, а Toast з нього — це падіння.
                          if (error == null) return;
                          runOnUiThread(
                              new Runnable() {
                                @Override
                                public void run() {
                                  toast("Не вдалося стерти слід у групі");
                                }
                              });
                        }
                      });
                }
                prefs.setCode("");
                // нова водяна позначка: у новій групі старі id не мають значення
                codeInput.setText("");
                mapLoaded = false;
                showTab(TAB_GROUP);
                render();
              }
            })
        .show();
  }

  /**
   * Стан трекера — чесний, а не «користувач натиснув кнопку».
   *
   * Раніше тут було лише «Трекер працює у фоні», і саме через це було
   * незрозуміло, надсилає застосунок точки чи ні: коли процес убито або
   * мережа лягла, екран всё одно показував зелену крапку.
   */
  private void render() {
    boolean hasGroup = prefs.configured();

    groupEmpty.setVisibility(hasGroup ? View.GONE : View.VISIBLE);
    groupReady.setVisibility(hasGroup ? View.VISIBLE : View.GONE);

    if (hasGroup) {
      codeValue.setText(prefs.mapUrl());
      if (codeInput.getText().toString().isEmpty()) codeInput.setText(prefs.code());
    }
    updateTabAvailability();

    boolean tracking = isTracking();
    toggle.setText(tracking ? R.string.btn_stop : R.string.btn_start);

    int dotColor;
    if (tracking) {
      status.setText(trackingStatus());
      String error = prefs.lastSendError();
      // Помилка важливіша за «працює»: саме вона пояснює, чому точка не рухається.
      if (!error.isEmpty()) dotColor = Color.parseColor("#DC2626");
      else dotColor = Color.parseColor("#16A34A");
    } else if (hasGroup) {
      status.setText(R.string.status_ready);
      dotColor = Color.parseColor("#D97706");
    } else {
      status.setText(R.string.status_no_group);
      dotColor = Color.parseColor("#9CA3AF");
    }
    statusDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(dotColor));
  }

  private String trackingStatus() {
    String error = prefs.lastSendError();
    if (!error.isEmpty()) return "Не надсилається: " + error;

    long sent = prefs.lastSentAt();
    if (sent <= 0) return getString(R.string.status_on) + " — чекаємо першу точку";

    long age = System.currentTimeMillis() - sent;
    // Понад годину без точок — це вже не нормально, а ознака проблеми.
    String ago =
        DateUtils.getRelativeTimeSpanString(
                sent, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            .toString();
    return "Остання точка: " + ago + (age > 60 * 60 * 1000L ? " (завжди так довго?)" : "");
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

    Intent service = new Intent(this, TrackerService.class);
    service.setAction("start");
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
    else startService(service);

    prefs.setTracking(true);
    prefs.setLastSendError("");
    render();
  }

  private void stopTracking() {
    Intent service = new Intent(this, TrackerService.class);
    service.setAction("stop");
    stopService(service);

    prefs.setTracking(false);
    render();
  }

  private void toast(String text) {
    if (text == null) return;
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
  }

  @Override
  public void onRequestPermissionsResult(
      int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    if (code == REQ_LOCATION) {
      if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
        startTracking();
      } else {
        toast("Без геолокації трекер не працює");
      }
    }
  }
}