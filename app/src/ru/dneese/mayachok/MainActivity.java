package ru.dneese.mayachok;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Мапа — це перший і головний екран. Коли група вже є, застосунок відкриває
 * одразу мапу, а всі налаштування ховаються у висувному меню ліворуч.
 *
 * Трекера ніхто не «вмикає»: щойно є група й дозвіл на геолокацію, він
 * стартує сам і працює у фоні. Кнопки «Почати» більше немає — дитині чи
 * батькам не треба нічого пам'ятати.
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

  /** Ширина висувного меню. Має збігатися з шириною @id/drawer у layout. */
  private static final int DRAWER_WIDTH_DP = 320;
  private static final long DRAWER_MS = 220L;

  private Prefs prefs;
  private Api api;

  private FrameLayout mapPanel;
  private View appbar;
  private View appbarLine;
  private View onboardingPanel;
  private WebView mapView;
  private View mapError;

  private View scrim;
  private View drawer;
  private ImageButton menuButton;
  private ImageButton drawerClose;

  private View statusDot;
  private View mapStatusDot;
  private TextView status;
  private TextView statusDetail;

  private EditText nameInput;
  private EditText nameMenuInput;
  private EditText codeInput;
  private Button createButton;
  private Button joinButton;

  private TextView codeValue;

  private Button[] intervalChips;
  private CheckBox diagBox;
  private TextView diagState;

  private boolean drawerOpen = false;
  private boolean mapStarted = false;
  private boolean autoStartPrompted = false;

  private final Handler ticker = new Handler(Looper.getMainLooper());

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    prefs = new Prefs(this);
    api = new Api(this);

    mapPanel = findViewById(R.id.map_panel);
    appbar = findViewById(R.id.appbar);
    appbarLine = findViewById(R.id.appbar_line);
    onboardingPanel = findViewById(R.id.onboarding_panel);
    mapView = findViewById(R.id.map_view);

    scrim = findViewById(R.id.scrim);
    drawer = findViewById(R.id.drawer);
    menuButton = findViewById(R.id.menu);
    drawerClose = findViewById(R.id.drawer_close);

    statusDot = findViewById(R.id.status_dot);
    mapStatusDot = findViewById(R.id.map_status_dot);
    status = findViewById(R.id.status);
    statusDetail = findViewById(R.id.status_detail);

    nameInput = findViewById(R.id.name);
    nameMenuInput = findViewById(R.id.name_menu);
    codeInput = findViewById(R.id.code);
    createButton = findViewById(R.id.create);
    joinButton = findViewById(R.id.join);

    codeValue = findViewById(R.id.code_value);
    diagBox = findViewById(R.id.diag);
    diagState = findViewById(R.id.diag_state);

    nameInput.setText(prefs.name());
    nameMenuInput.setText(prefs.name());
    codeInput.setText(prefs.code());

    buildIntervalChips();
    setUpDrawer();
    setUpActions();
    setUpMap();

    if (prefs.configured() && prefs.memberToken().isEmpty()) {
      prefs.setCode("");
      toast("Приєднайтеся до групи ще раз");
    }
    handleDeepLink(getIntent());
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
              if (TrackerService.running) {
                Intent service = new Intent(this, TrackerService.class);
                service.setAction("interval");
                startService(service);
              }
              render();
            }
          });
    }
  }

  // --- висувне меню ---

  private void setUpDrawer() {
    menuButton.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            openDrawer();
          }
        });
    drawerClose.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            closeDrawer();
          }
        });
    scrim.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            closeDrawer();
          }
        });

    // Ім'я в меню зберігається на кожну зміну — трекер читає його під час
    // надсилання, тож нове ім'я підхоплюється без жодного «Зберегти».
    nameMenuInput.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {}

          @Override
          public void afterTextChanged(Editable s) {
            prefs.setName(s.toString());
          }
        });
  }

  private int drawerWidthPx() {
    return dp(DRAWER_WIDTH_DP);
  }

  private void openDrawer() {
    if (drawerOpen) return;
    drawerOpen = true;
    drawer.setVisibility(View.VISIBLE);
    scrim.setVisibility(View.VISIBLE);
    drawer.setTranslationX(-drawerWidthPx());
    scrim.setAlpha(0f);
    drawer
        .animate()
        .translationX(0f)
        .setDuration(DRAWER_MS)
        .setInterpolator(new DecelerateInterpolator())
        .start();
    scrim.animate().alpha(1f).setDuration(DRAWER_MS).start();
  }

  private void closeDrawer() {
    if (!drawerOpen) return;
    drawerOpen = false;
    prefs.setName(nameMenuInput.getText().toString());
    drawer
        .animate()
        .translationX(-drawerWidthPx())
        .setDuration(DRAWER_MS)
        .setInterpolator(new AccelerateInterpolator())
        .withEndAction(
            new Runnable() {
              @Override
              public void run() {
                if (!drawerOpen) drawer.setVisibility(View.GONE);
              }
            })
        .start();
    scrim
        .animate()
        .alpha(0f)
        .setDuration(DRAWER_MS)
        .withEndAction(
            new Runnable() {
              @Override
              public void run() {
                if (!drawerOpen) scrim.setVisibility(View.GONE);
              }
            })
        .start();
  }

  private void setUpActions() {
    onClick(R.id.create, new Runnable() { @Override public void run() { createGroup(); } });
    onClick(R.id.join, new Runnable() { @Override public void run() { joinGroup(); } });
    onClick(R.id.share, new Runnable() { @Override public void run() { shareInvite(); } });
    onClick(R.id.copy, new Runnable() { @Override public void run() { copy(prefs.mapUrl()); } });
    onClick(R.id.forget, new Runnable() { @Override public void run() { confirmForget(); } });
    onClick(R.id.github, new Runnable() { @Override public void run() { openUrl(prefs.githubUrl()); } });

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
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setSupportMultipleWindows(false);
    settings.setMediaPlaybackRequiresUserGesture(true);

    mapView.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request == null ? null : request.getUrl());
          }

          @Override
          @SuppressWarnings("deprecation")
          public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url == null ? null : Uri.parse(url));
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
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

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

  private boolean handleUrl(Uri uri) {
    if (uri == null) return false;
    String scheme = uri.getScheme();
    String host = uri.getHost();
    String mapHost = Uri.parse(prefs.mapUrl()).getHost();
    if ("https".equalsIgnoreCase(scheme) && host != null && host.equals(mapHost)) {
      return false;
    }
    openUrl(uri.toString());
    return true;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override
  public void onBackPressed() {
    if (drawerOpen) {
      closeDrawer();
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
    // Мапа — головний екран, тож її таймери живуть, поки екран відкритий.
    if (prefs.configured()) {
      mapView.resumeTimers();
      mapView.onResume();
    }
    retryPendingLeave();
    // Автозапуск: щойно є група й дозвіл — трекер піднімається сам.
    autoStart();
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
    if (prefs.configured()) return; // уже в групі — запрошення не змінює нічого
    codeInput.setText(code);
    if (!prefs.name().isEmpty()) {
      joinGroup();
    } else {
      toast("Введіть своє ім’я і натисніть «Приєднатися»");
    }
  }

  // --- група ---

  private void saveBasics() {
    prefs.setName(nameInput.getText().toString());
    prefs.setName(nameMenuInput.getText().toString());
  }

  private void createGroup() {
    saveBasics();
    createButton.setEnabled(false);
    createButton.setText("Створюю…");

    api.createGroup(
        new Api.GroupCallback() {
          @Override
          public void onResult(final String code, final String token, final String error) {
            runOnUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    createButton.setEnabled(true);
                    createButton.setText(R.string.btn_create_group);
                    if (code != null) {
                      prefs.setCode(code);
                      prefs.setMemberToken(token);
                      toast("Групу створено");
                      render();
                      autoStart();
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
      name = nameMenuInput.getText().toString().trim();
    }
    if (name.isEmpty()) {
      toast("Введіть своє ім’я");
      return;
    }

    joinButton.setEnabled(false);
    api.join(
        code,
        name,
        new Api.JoinCallback() {
          @Override
          public void onResult(final String token, final String error) {
            runOnUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    joinButton.setEnabled(true);
                    if (error != null) {
                      toast(error);
                      render();
                      return;
                    }
                    prefs.setCode(code);
                    prefs.setMemberToken(token);
                    prefs.setName(name);
                    toast("Ви в групі");
                    render();
                    autoStart();
                  }
                });
          }
        });
  }

  private void retryPendingLeave() {
    if (!prefs.leavePending()) return;
    final String code = prefs.pendingLeaveCode();
    final String token = prefs.pendingLeaveToken();
    if (!isCode(code)) {
      prefs.setLeavePending(false, "", "");
      return;
    }
    api.leave(
        code,
        token,
        new Api.Callback() {
          @Override
          public void onResult(String error) {
            if (error == null) prefs.setLeavePending(false, "", "");
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
                final String leavingToken = prefs.memberToken();
                if (isCode(leavingCode)) {
                  prefs.setLeavePending(true, leavingCode, leavingToken);
                  api.leave(
                      leavingCode,
                      leavingToken,
                      new Api.Callback() {
                        @Override
                        public void onResult(String error) {
                          if (error == null) {
                            prefs.setLeavePending(false, "", "");
                            return;
                          }
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
                closeDrawer();
                render();
              }
            })
        .show();
  }

  /** Стан трекера — чесний: коли пішла точка і чому не йде. */
  private void render() {
    boolean hasGroup = prefs.configured();

    mapPanel.setVisibility(hasGroup ? View.VISIBLE : View.GONE);
    appbar.setVisibility(hasGroup ? View.VISIBLE : View.GONE);
    appbarLine.setVisibility(hasGroup ? View.VISIBLE : View.GONE);
    onboardingPanel.setVisibility(hasGroup ? View.GONE : View.VISIBLE);

    if (hasGroup) {
      codeValue.setText(prefs.mapUrl());
      if (!mapStarted) {
        mapStarted = true;
        mapView.loadUrl(prefs.mapUrl());
        mapView.resumeTimers();
        mapView.onResume();
      }
    } else {
      mapStarted = false;
      if (drawerOpen) closeDrawer();
    }

    String name = prefs.name();
    if (!name.equals(nameInput.getText().toString().trim())) nameInput.setText(name);
    if (!name.equals(nameMenuInput.getText().toString().trim())) nameMenuInput.setText(name);

    boolean tracking = isTracking();
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
      status.setText(hasGroup ? R.string.status_starting : R.string.status_no_group);
      statusDetail.setVisibility(View.GONE);
      dotColor = getColor(hasGroup ? R.color.warn : R.color.text_faint);
    }
    ColorStateList dot = ColorStateList.valueOf(dotColor);
    statusDot.setBackgroundTintList(dot);
    mapStatusDot.setBackgroundTintList(dot);

    for (int i = 0; i < intervalChips.length; i++) {
      intervalChips[i].setSelected(INTERVALS[i] == prefs.intervalSeconds());
    }

    diagBox.setChecked(prefs.diagnostics());
    diagBox.setEnabled(hasGroup);
    boolean bgMissing =
        hasGroup
            && tracking
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            && !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    if (bgMissing) {
      diagState.setVisibility(View.VISIBLE);
      diagState.setText(R.string.diag_bg_hint);
    } else if (hasGroup && prefs.diagnostics() && !Diag.ignoresBatteryOptimizations()) {
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

  private boolean hasLocationPermission() {
    return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
  }

  private boolean hasPermission(String permission) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
    return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
  }

  /**
   * Автозапуск трекера.
   *
   * Ніякої кнопки «Почати» немає: якщо група є, трекер має працювати. Єдиний
   * раз, коли ми просимо геолокацію, — коли її ще не дали; далі все тихо.
   * Самовідновлення після вбитого процесу теж тут: щойно екран відкрито,
   * мертвий сервіс піднімається знову.
   */
  private void autoStart() {
    if (!prefs.configured()) return;
    if (prefs.isTracking() && TrackerService.running) return;

    if (!hasLocationPermission()) {
      if (!autoStartPrompted) {
        autoStartPrompted = true;
        requestPermissions(
            new String[] {
              Manifest.permission.ACCESS_FINE_LOCATION,
              Manifest.permission.ACCESS_COARSE_LOCATION
            },
            REQ_LOCATION);
      }
      return;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
      requestNotifications();
      return;
    }

    startServiceIntent();
    ensureBackgroundLocation();
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
    prefs.setServiceRunning(false);
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
      boolean granted = false;
      for (int result : results) {
        if (result == PackageManager.PERMISSION_GRANTED) granted = true;
      }
      if (granted) {
        autoStart();
      } else {
        toast("Без геолокації трекер не працює");
      }
    } else if (code == REQ_NOTIFY) {
      autoStart();
    }
  }
}
