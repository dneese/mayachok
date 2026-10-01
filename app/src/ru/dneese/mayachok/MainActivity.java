package ru.dneese.mayachok;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.app.Activity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Один екран, три кроки: хто я → яка група → запустити трекер.
 *
 * Код групи створює сам застосунок (сервер генерує) або вводить той,
 * хто вже має групу. Адреси сервера немає в інтерфейсі — вона незмінна.
 */
public final class MainActivity extends Activity {
  private static final int REQ_LOCATION = 10;
  private static final int REQ_NOTIFICATIONS = 11;

  private static final int[] INTERVALS = {10, 30, 60, 120};

  private Prefs prefs;
  private Api api;

  private TextView status;
  private EditText nameInput;

  // блок «групи»
  private View groupEmpty;
  private View groupReady;
  private TextView codeValue;
  private EditText codeInput;
  private Button createButton;
  private Button joinButton;

  private Spinner intervalSpinner;
  private Button toggle;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    prefs = new Prefs(this);
    api = new Api(this);

    status = findViewById(R.id.status);
    nameInput = findViewById(R.id.name);

    groupEmpty = findViewById(R.id.group_empty);
    groupReady = findViewById(R.id.group_ready);
    codeValue = findViewById(R.id.code_value);
    codeInput = findViewById(R.id.code);
    createButton = findViewById(R.id.create);
    joinButton = findViewById(R.id.join);

    intervalSpinner = findViewById(R.id.interval);
    toggle = findViewById(R.id.toggle);

    nameInput.setText(prefs.name());
    codeInput.setText(prefs.code());

    String[] labels = new String[INTERVALS.length];
    for (int i = 0; i < INTERVALS.length; i++) {
      labels[i] = i == 0 ? INTERVALS[i] + " с (часто)" : INTERVALS[i] + " с";
    }
    intervalSpinner.setAdapter(
        new android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_dropdown_item, labels));
    for (int i = 0; i < INTERVALS.length; i++) {
      if (INTERVALS[i] == prefs.intervalSeconds()) {
        intervalSpinner.setSelection(i);
        break;
      }
    }

    // --- дії ---
    createButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        createGroup();
      }
    });

    findViewById(R.id.join).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        joinGroup();
      }
    });

    findViewById(R.id.share).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        shareInvite();
      }
    });

    findViewById(R.id.copy).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        copy(prefs.mapUrl());
      }
    });

    findViewById(R.id.map).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        openUrl(prefs.mapUrl());
      }
    });

    findViewById(R.id.forget).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        prefs.setCode("");
        codeInput.setText("");
        stopTracking();
        render();
      }
    });

    findViewById(R.id.github).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        openUrl(prefs.githubUrl());
      }
    });

    findViewById(R.id.apk).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        openUrl(prefs.apkUrl());
      }
    });

    toggle.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        saveBasics();
        if (isTracking()) stopTracking();
        else startTracking();
      }
    });

    handleDeepLink(getIntent());
    render();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleDeepLink(intent);
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

  @Override
  protected void onResume() {
    super.onResume();
    render();
  }

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
            // відповідь приходить у фоновому потоці — повертаємося в UI-потік
            runOnUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    createButton.setEnabled(true);
                    createButton.setText(R.string.btn_create_group);
                    if (code != null) {
                      prefs.setCode(code);
                      toast("Групу створено");
                      // щойно створена група — одразу запускаємо трекер
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

  /**
   * Приєднання за посиланням: користувач вставляє саме посилання
   * з запрошення, а код ми дістаємо з нього самі.
   * Після успіху трекер стартує без зайвих кроків — «ввів ім’я і все».
   */
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

  /** Показує або ховає блоки залежно від того, чи є вже група. */
  private void render() {
    boolean hasGroup = prefs.configured();

    groupEmpty.setVisibility(hasGroup ? View.GONE : View.VISIBLE);
    groupReady.setVisibility(hasGroup ? View.VISIBLE : View.GONE);

    if (hasGroup) {
      // показуємо посилання, а не код: саме його надсилають іншим
      codeValue.setText(prefs.mapUrl());
      codeInput.setText(prefs.code());
    }

    boolean tracking = isTracking();
    toggle.setText(tracking ? R.string.btn_stop : R.string.btn_start);
    if (tracking) status.setText(R.string.status_on);
    else if (hasGroup) status.setText(R.string.status_ready);
    else status.setText(R.string.status_no_group);
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
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (ActivityNotFoundException error) {
      toast("Немає програми, щоб відкрити посилання");
    }
  }

  private boolean isTracking() {
    return prefs.isTracking();
  }

  /** На API < 23 дозволи даються під час встановлення, тож перевірка не потрібна. */
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
  }

  private void stopTracking() {
    Intent service = new Intent(this, TrackerService.class);
    service.setAction("stop");
    // stopService() зупиняє і foreground service — окремий метод не потрібен.
    stopService(service);

    prefs.setTracking(false);
    render();
  }

  private void toast(String text) {
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
      // і без дозволу на сповіщення починаємо: сервіс мусить стартувати
      if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startTracking();
    }
  }
}
