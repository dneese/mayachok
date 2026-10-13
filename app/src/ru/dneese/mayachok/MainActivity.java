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
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Один екран і дві вкладки: Група → Мапа.
 *
 * Вкладки перемикаються нижнім меню, а апаратна кнопка «назад» завжди
 * повертає на Групу. Ключова деталь: назад НІКОЛИ не віддається WebView, бо
 * той інакше повертається на about:blank — це давало білий екран.
 */
public final class MainActivity extends Activity {
  private static final int REQ_LOCATION = 10;
  private static final int REQ_BACKGROUND = 11;
  private static final int REQ_NOTIFY = 12;

  /**
   * Як часто трекер надсилає точку.
   *
   * Значення — це КОНТРОЛЬ батареї, а не прихована оптимізація. Раніше
   * застосунок сам вирішував, коли можна не надсилати (розтягував інтервал
   * до півгодини), і через це точки могли мовчати годинами. Тепер він
   * надсилає щоразу — наскільки часто, вирішує людина тут.
   */
  private static final int[] INTERVALS = {30, 60, 120, 300, 600, 900};
  private static final int[] INTERVAL_LABELS = {
    R.string.interval_30, R.string.interval_60, R.string.interval_120,
    R.string.interval_300, R.string.interval_600, R.string.interval_900,
  };

  private static final int TAB_GROUP = 0;
  private static final int TAB_MAP = 1;

  private static final int[] TITLES = {R.string.app_name, R.string.tab_map};

  private Prefs prefs;
  private Api api;

  private View statusBanner;
  private View statusDot;
  private TextView status;
  private TextView statusDetail;
  private TextView title;
  private ImageButton back;
  private EditText nameInput;

  private View groupEmpty;
  private View groupReady;
  private TextView codeValue;
  private EditText codeInput;
  private Button createButton;
  private Button joinButton;

  private Button[] intervalChips;
  private Button toggle;
  private CheckBox diagBox;
  private TextView diagState;

  private View mainPanel;
  private LinearLayout mapPanel;
  private View[] tabs;
  private TextView[] tabTexts;
  private ImageView[] tabIcons;
  private WebView mapView;
  private View mapError;

  private int currentTab = TAB_GROUP;

  private final Handler ticker = new Handler(Looper.getMainLooper());

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    prefs = new Prefs(this);
    api = new Api(this);

    statusBanner = findViewById(R.id.status_banner);
    statusDot = findViewById(R.id.status_dot);
    status = findViewById(R.id.status);
    statusDetail = findViewById(R.id.status_detail);
    title = findViewById(R.id.title);
    back = findViewById(R.id.back);
    nameInput = findViewById(R.id.name);

    groupEmpty = findViewById(R.id.group_empty);
    groupReady = findViewById(R.id.group_ready);
    codeValue = findViewById(R.id.code_value);
    codeInput = findViewById(R.id.code);
    createButton = findViewById(R.id.create);
    joinButton = findViewById(R.id.join);

    toggle = findViewById(R.id.toggle);
    diagBox = findViewById(R.id.diag);
    diagState = findViewById(R.id.diag_state);

    mainPanel = findViewById(R.id.main_panel);
    mapPanel = findViewById(R.id.map_panel);
    mapView = findViewById(R.id.map_view);

    tabs = new View[] {findViewById(R.id.tab_group), findViewById(R.id.tab_map)};
    tabTexts =
        new TextView[] {findViewById(R.id.tab_group_text), findViewById(R.id.tab_map_text)};
    tabIcons =
        new ImageView[] {findViewById(R.id.tab_group_icon), findViewById(R.id.tab_map_icon)};

    nameInput.setText(prefs.name());
    codeInput.setText(prefs.code());

    buildIntervalChips();
    setUpTabs();
    setUpActions();
    setUpMap();

    handleDeepLink(getIntent());
    showTab(TAB_GROUP);
    render();
  }

  /** Чипи з інтервалами: видно всі варіанти й обраний одночасно. */
  private void buildIntervalChips() {
    LinearLayout box = findViewById(R.id.chips);
    intervalChips = new Button[INTERVALS.length];
    for (int i = 0; i < INTERVALS.length; i++) {
      final int index = i;
      Button chip = new Button(this);
      chip.setText(INTERVAL_LABELS[i]);
      chip.setAllCaps(false);
      chip.setBackgroundResource(R.drawable.bg_chip);
      chip.setTextColor(getResources().getColorStateList(R.color.chip_text, null));
      LinearLayout.LayoutParams params =
          new LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      params.setMargins(0, 0, 8, 8);
      box.addView(chip, params);
      intervalChips[i] = chip;

      chip.setOnClickListener(
          new View.OnClickListener() {
            @Override
            public void onClick(View view) {
              prefs.setIntervalSeconds(INTERVALS[index]);
              render();
            }
          });
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
    onClick(R.id.create, new Runnable() { @Override public void run() { createGroup(); } });
    onClick(R.id.join, new Runnable() { @Override public void run() { joinGroup(); } });
    onClick(R.id.share, new Runnable() { @Override public void run() { shareInvite(); } });
    onClick(R.id.copy, new Runnable() { @Override public void run() { copy(prefs.mapUrl()); } });
    onClick(R.id.map, new Runnable() { @Override public void run() { showTab(TAB_MAP); } });
    onClick(R.id.forget, new Runnable() { @Override public void run() { confirmForget(); } });
    onClick(R.id.github, new Runnable() { @Override public void run() { openUrl(prefs.githubUrl()); } });
    onClick(R.id.back, new Runnable() { @Override public void run() { goBack(); } });
    onClick(
        R.id.toggle,
        new Runnable() {
          @Override
          public void run() {
            saveBasics();
            if (isTracking()) stopTracking();
            else startTracking();
          }
        });

    diagBox.setOnCheckedChangeListener(
        new CompoundButton.OnCheckedChangeListener() {
          @Override
          public void onCheckedChanged(CompoundButton button, boolean checked) {
            // render() виставляє стан програмно, тож реагуємо лише на тап.
            if (button.isPressed()) onDiagnosticsToggled(checked);
          }
        });
  }

  private void onClick(int id, final Runnable action) {
    findViewById(id)
        .setOnClickListener(
            new View.OnClickListener() {
              @Override
              public void onClick(View view) {
                action.run();
              }
            });
  }

  private void onDiagnosticsToggled(boolean enabled) {
    prefs.setDiagnostics(enabled);
    if (enabled) {
      Diag.attach(this);
      Diag.event(
          Diag.PERMISSION,
          hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ? 1 : 0,
          Diag.powerSaveMode() ? 1 : 0,
          Diag.backgroundRestricted() ? 1 : 0,
          "діагностику ввімкнено");

      if (!Diag.ignoresBatteryOptimizations() || Diag.powerSaveMode()) {
        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.label_diag)
            .setMessage(R.string.diag_battery_hint)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                R.string.diag_open_settings,
                new android.content.DialogInterface.OnClickListener() {
                  @Override
                  public void onClick(android.content.DialogInterface dialog, int which) {
                    try {
                      startActivity(Diag.batterySettingsIntent());
                    } catch (Exception error) {
                      toast("Не вдалося відкрити налаштування");
                    }
                  }
                })
            .show();
      }
    } else {
      Diag.flush();
    }
    render();
  }

  /**
   * WebView без залежностей: та сама сторінка, що й у браузері.
   */
  private void setUpMap() {
    WebSettings settings = mapView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setSupportMultipleWindows(false);
    settings.setMediaPlaybackRequiresUserGesture(true);

    mapView.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            // Мапа живе на нашому ж Worker, тож визначаємо свій хост із mapUrl(),
            // а не хардкодимо: інакше при зміні адреси посилання в карті
            // почали б відкриватись у браузері замість убудованої мапи.
            String host = uri == null ? null : uri.getHost();
            if (host != null && host.equals(Uri.parse(prefs.mapUrl()).getHost())) return false;
            openUrl(uri == null ? "" : uri.toString());
            return true;
          }

          @Override
          public void onPageFinished(WebView view, String url) {
            mapError.setVisibility(View.GONE);
            mapView.setVisibility(View.VISIBLE);
          }

          @Override
          public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request == null || !request.isForMainFrame()) return;
            showMapError();
          }

          @Override
          @SuppressWarnings("deprecation")
          public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            showMapError();
          }
        });

    LinearLayout errorBox = new LinearLayout(this);
    errorBox.setOrientation(LinearLayout.VERTICAL);
    errorBox.setGravity(Gravity.CENTER);
    errorBox.setPadding(dp(28), dp(24), dp(28), dp(24));
    errorBox.setVisibility(View.GONE);
    errorBox.setLayoutParams(
        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

    TextView errorText = new TextView(this);
    errorText.setText(R.string.map_error);
    errorText.setTextColor(Color.parseColor("#101828"));
    errorText.setTextSize(17);
    errorText.setGravity(Gravity.CENTER);
    errorBox.addView(errorText);

    TextView errorHint = new TextView(this);
    errorHint.setText(R.string.map_hint);
    errorHint.setTextColor(Color.parseColor("#667085"));
    errorHint.setTextSize(14);
    errorHint.setGravity(Gravity.CENTER);
    errorHint.setPadding(0, dp(10), 0, dp(18));
    errorBox.addView(errorHint);

    Button retry = new Button(this);
    retry.setText(R.string.map_retry);
    retry.setAllCaps(false);
    retry.setTextColor(Color.WHITE);
    retry.setBackgroundResource(R.drawable.bg_btn_primary);
    retry.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            mapView.loadUrl(prefs.mapUrl());
          }
        });
    errorBox.addView(retry);

    mapPanel.addView(errorBox, 1);
    mapError = errorBox;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void showTab(int tab) {
    if (tab != TAB_GROUP && !prefs.configured()) return;

    currentTab = tab;
    mainPanel.setVisibility(tab == TAB_GROUP ? View.VISIBLE : View.GONE);
    mapPanel.setVisibility(tab == TAB_MAP ? View.VISIBLE : View.GONE);

    for (int i = 0; i < tabs.length; i++) {
      boolean active = i == tab;
      tabs[i].setSelected(active);
      tabTexts[i].setTextColor(getColor(active ? R.color.brand : R.color.text_muted));
      tabIcons[i].setAlpha(active ? 1f : 0.55f);
    }

    title.setText(TITLES[tab]);
    back.setVisibility(tab == TAB_GROUP ? View.INVISIBLE : View.VISIBLE);

    if (tab == TAB_MAP) {
      if (mapView.getUrl() == null) mapView.loadUrl(prefs.mapUrl());
      mapView.post(
          new Runnable() {
            @Override
            public void run() {
              mapView.invalidate();
            }
          });
    }

    // Найважливіше для батареї. Сторінка мапи опитує API таймером, і
    // setInterval у JavaScript НЕ зупиняється, коли вкладка просто
    // прихована. Без pauseTimers() ми робимо десятки тисяч запитів на добу.
    if (tab == TAB_MAP) {
      mapView.resumeTimers();
      mapView.onResume();
    } else {
      mapView.pauseTimers();
      mapView.onPause();
      mapView.stopLoading();
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

  private void showMapError() {
    mapView.setVisibility(View.GONE);
    mapError.setVisibility(View.VISIBLE);
  }

  // --- життєвий цикл ---

  @Override
  protected void onResume() {
    super.onResume();
    render();
    if (currentTab == TAB_MAP) {
      mapView.resumeTimers();
      mapView.onResume();
    }
    // Стан показує «остання точка: N хв тому», тож перечитуємо його
    // раз на півхвилини, поки екран відкритий.
    ticker.postDelayed(statusTick, 30 * 1000L);
  }

  @Override
  protected void onPause() {
    super.onPause();
    ticker.removeCallbacks(statusTick);
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
                final String leavingCode = prefs.code();
                if (isCode(leavingCode)) {
                  api.leave(
                      leavingCode,
                      new Api.Callback() {
                        @Override
                        public void onResult(String error) {
                          if (error == null) return;
                          // Обов'язково через runOnUiThread: callback приходить
                          // з фонового потоку, а Toast з нього — це падіння.
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
                codeInput.setText("");
                showTab(TAB_GROUP);
                render();
              }
            })
        .show();
  }

  /** Стан трекера — чесний: коли пішла точка і чому не йде. */
  private void render() {
    boolean hasGroup = prefs.configured();

    groupEmpty.setVisibility(hasGroup ? View.GONE : View.VISIBLE);
    groupReady.setVisibility(hasGroup ? View.VISIBLE : View.GONE);

    if (hasGroup) {
      codeValue.setText(prefs.mapUrl());
      if (codeInput.getText().toString().isEmpty()) codeInput.setText(prefs.code());
    }

    boolean tracking = isTracking();
    toggle.setText(tracking ? R.string.btn_stop : R.string.btn_start);

    String error = prefs.lastSendError();
    int dotColor;
    if (tracking) {
      status.setText(error.isEmpty() ? getString(R.string.status_on) : getString(R.string.status_send_failed));
      statusDetail.setVisibility(View.VISIBLE);
      if (!error.isEmpty()) {
        statusDetail.setText(error);
        dotColor = getColor(R.color.danger);
      } else {
        long sent = prefs.lastSentAt();
        if (sent <= 0) {
          statusDetail.setText(getString(R.string.status_searching));
        } else {
          statusDetail.setText(
              "Остання точка: "
                  + DateUtils.getRelativeTimeSpanString(
                      sent, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        }
        dotColor = getColor(R.color.ok);
      }
    } else {
      status.setText(hasGroup ? R.string.status_ready : R.string.status_no_group);
      statusDetail.setVisibility(View.GONE);
      dotColor = getColor(hasGroup ? R.color.warn : R.color.text_faint);
    }
    statusDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(dotColor));

    for (int i = 0; i < intervalChips.length; i++) {
      intervalChips[i].setSelected(INTERVALS[i] == prefs.intervalSeconds());
    }

    diagBox.setChecked(prefs.diagnostics());
    diagBox.setEnabled(hasGroup);
    if (hasGroup && prefs.diagnostics() && !Diag.ignoresBatteryOptimizations()) {
      diagState.setVisibility(View.VISIBLE);
      diagState.setText("Оптимізацію батареї не вимкнено — точки можуть не йти");
    } else {
      diagState.setVisibility(View.GONE);
    }
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

    startServiceIntent();
    ensureBackgroundLocation();
    requestNotifications();
    render();
  }

  private void startServiceIntent() {
    Intent service = new Intent(this, TrackerService.class);
    service.setAction("start");
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
    else startService(service);

    prefs.setTracking(true);
    prefs.setLastSendError("");
  }

  /**
   * Фоновий дозвіл потрібен лише для підйому з фону (перезавантаження,
   * змах застосунку). Поки застосунок на екрані чи сервіс живий — точки йдуть
   * без нього. Тому просимо один раз і не блокуємо запуск.
   */
  private void ensureBackgroundLocation() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
    if (hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return;
    if (prefs.backgroundAsked()) return;

    prefs.setBackgroundAsked(true);
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
      requestPermissions(
          new String[] {Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BACKGROUND);
    } else {
      // Android 11+ діалог для цього дозволу не показує — лише налаштування.
      toast("Увімкніть «Дозволити увесь час» — і трекер підніматиметься сам після перезавантаження");
      try {
        startActivity(
            new Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)));
      } catch (Exception ignored) {
        // Налаштування недоступні — не страшно, трекінг працює й без цього.
      }
    }
  }

  /** Сповіщення трекера: постійне, щоб видно було, що він увімкнений. */
  private void requestNotifications() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
    if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return;
    requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
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
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
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