package ru.dneese.mayachok;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.app.Activity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/** Налаштування та запуск/зупинка трекера. */
public final class MainActivity extends Activity {
  private static final int REQ_LOCATION = 10;
  private static final int REQ_NOTIFICATIONS = 11;

  private Prefs prefs;
  private TextView status;
  private EditText nameInput;
  private EditText codeInput;
  private EditText apiInput;
  private Spinner intervalSpinner;
  private Button toggle;

  private static final int[] INTERVALS = {10, 30, 60, 120};

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    prefs = new Prefs(this);

    status = findViewById(R.id.status);
    nameInput = findViewById(R.id.name);
    codeInput = findViewById(R.id.code);
    apiInput = findViewById(R.id.api);
    intervalSpinner = findViewById(R.id.interval);
    toggle = findViewById(R.id.toggle);

    nameInput.setText(prefs.name());
    codeInput.setText(prefs.code());
    apiInput.setText(prefs.api());

    String[] labels = new String[INTERVALS.length];
    for (int i = 0; i < INTERVALS.length; i++) labels[i] = INTERVALS[i] + " с";
    intervalSpinner.setAdapter(
        new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, labels));
    for (int i = 0; i < INTERVALS.length; i++) {
      if (INTERVALS[i] == prefs.intervalSeconds()) {
        intervalSpinner.setSelection(i);
        break;
      }
    }

    findViewById(R.id.save).setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            save();
          }
        });

    toggle.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            save();
            if (isTracking()) stopTracking();
            else startTracking();
          }
        });
  }

  @Override
  protected void onResume() {
    super.onResume();
    updateStatus();
  }

  private void save() {
    prefs.setName(nameInput.getText().toString());
    prefs.setCode(codeInput.getText().toString());
    prefs.setApi(apiInput.getText().toString());
    prefs.setIntervalSeconds(INTERVALS[intervalSpinner.getSelectedItemPosition()]);

    if (prefs.code().length() < 8) {
      Toast.makeText(this, "Код групи закороткий", Toast.LENGTH_LONG).show();
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
      Toast.makeText(this, "Спершу введіть код групи", Toast.LENGTH_LONG).show();
      return;
    }
    if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
      requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
      return;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
    }

    Intent service = new Intent(this, TrackerService.class);
    service.setAction("start");
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
    else startService(service);

    prefs.setTracking(true);
    updateStatus();
  }

  private void stopTracking() {
    Intent service = new Intent(this, TrackerService.class);
    service.setAction("stop");
    // stopService() зупиняє і foreground service — окремий метод не потрібен.
    stopService(service);

    prefs.setTracking(false);
    updateStatus();
  }

  private void updateStatus() {
    boolean tracking = isTracking();
    toggle.setText(tracking ? "Зупинити" : "Почати");
    status.setText(tracking ? "Трекер працює у фоні" : "Трекер зупинено");
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (requestCode == REQ_LOCATION) {
      if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
        startTracking();
      } else {
        Toast.makeText(this, "Без геолокації трекер не працює", Toast.LENGTH_LONG).show();
      }
    } else if (requestCode == REQ_NOTIFICATIONS) {
      startTracking();
    }
  }
}
