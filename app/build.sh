#!/usr/bin/env bash
#
# Збірка APK без Gradle: aapt2 → javac → d8 → apksigner.
# Працює і на Termux (пакети aapt2/d8/apksigner), і в CI (Android SDK).
#
set -euo pipefail

cd "$(dirname "$0")"

OUT="build"
VERSION_CODE=2
VERSION_NAME="1.2"
# Назва файлу в Releases лишається сталою: на неї посилаються застосунок,
# веб-сторінка, README й текст запрошення. Перейменування зламало б усі старі
# посилання, тож версію тримаємо в AndroidManifest.xml, а не в імені файлу.
APK_NAME="mayachok-1.0.apk"

# --- знаходимо інструменти: спершу локальний SDK, потім системні ---
find_android_jar() {
  if [ -n "${ANDROID_JAR:-}" ] && [ -f "$ANDROID_JAR" ]; then echo "$ANDROID_JAR"; return; fi
  for candidate in \
    "${ANDROID_HOME:-}/platforms/android-3[5-9]/android.jar" \
    "${ANDROID_SDK_ROOT:-}/platforms/android-3[5-9]/android.jar" \
    "${HOME}/android-sdk/platforms/android-3[5-9]/android.jar" \
    "${HOME}/.cache/gps-tracker/android.jar"; do
    [ -f "$candidate" ] && { echo "$candidate"; return; }
  done
  return 1
}

AAPT2="${AAPT2:-$(command -v aapt2 || true)}"
D8="${D8:-$(command -v d8 || true)}"
APKSIGNER="${APKSIGNER:-$(command -v apksigner || true)}"

if [ -n "${ANDROID_HOME:-}" ]; then
  BUILD_TOOLS="$(ls -1d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
  [ -n "$BUILD_TOOLS" ] && { AAPT2="$BUILD_TOOLS/aapt2"; APKSIGNER="$BUILD_TOOLS/apksigner"; }
fi

ANDROID_JAR_PATH="$(find_android_jar || true)"

echo "==> інструменти"
echo "    aapt2     : ${AAPT2:-НЕ ЗНАЙДЕНО}"
echo "    android.jar: ${ANDROID_JAR_PATH:-НЕ ЗНАЙДЕНО}"
echo "    d8        : ${D8:-НЕ ЗНАЙДЕНО}"
echo "    apksigner : ${APKSIGNER:-НЕ ЗНАЙДЕНО}"

for tool in AAPT2 D8 APKSIGNER; do
  if [ -z "${!tool}" ] || ! command -v "${!tool}" >/dev/null 2>&1; then
    case "$tool" in
      AAPT2)     echo "ПОМИЛКА: потрібен aapt2 (Termux: pkg install aapt2)" >&2 ;;
      D8)        echo "ПОМИЛКА: потрібен d8 (Termux: pkg install d8)" >&2 ;;
      APKSIGNER) echo "ПОМИЛКА: потрібен apksigner (Termux: pkg install apksigner)" >&2 ;;
    esac
    exit 1
  fi
done

if [ -z "$ANDROID_JAR_PATH" ]; then
  echo "ПОМИЛКА: не знайдено android.jar. Завантаж платформу Android 35:" >&2
  echo "  mkdir -p ~/.cache/gps-tracker && cd ~/.cache/gps-tracker" >&2
  echo "  curl -LO https://dl.google.com/android/repository/platform-35_r02.zip" >&2
  echo "  unzip -o platform-35_r02.zip 'android-15/android.jar' && mv android-15/android.jar ." >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"/{compiled,gen,classes,dex,tmp}

echo "==> 1/6 aapt2 compile (ресурси)"
"$AAPT2" compile --dir res -o "$OUT/compiled/res.zip"

echo "==> 2/6 aapt2 link (resources + manifest)"
"$AAPT2" link \
  -o "$OUT/tmp/base.apk" \
  -I "$ANDROID_JAR_PATH" \
  --manifest AndroidManifest.xml \
  -R "$OUT/compiled/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 21 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay

echo "==> 3/6 javac (Java → .class)"
find src -name '*.java' > "$OUT/sources.txt"
[ -d "$OUT/gen" ] && find "$OUT/gen" -name '*.java' >> "$OUT/sources.txt" || true

if ! javac \
      -source 8 -target 8 \
      -encoding UTF-8 \
      -nowarn \
      -classpath "$ANDROID_JAR_PATH" \
      -d "$OUT/classes" \
      @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  echo "ПОМИЛКА: javac завершився з помилками:" >&2
  cat "$OUT/javac.log" >&2
  exit 1
fi

if [ -s "$OUT/javac.log" ]; then
  echo "    попередження javac:"
  sed 's/^/      /' "$OUT/javac.log"
fi

if [ -z "$(find "$OUT/classes" -name '*.class' -print -quit)" ]; then
  echo "ПОМИЛКА: javac не створив жодного .class" >&2
  exit 1
fi

echo "==> 4/6 d8 (.class → classes.dex)"
find "$OUT/classes" -name '*.class' > "$OUT/classlist.txt"
"$D8" \
  --lib "$ANDROID_JAR_PATH" \
  --min-api 21 \
  --release \
  --output "$OUT/dex" \
  @"$OUT/classlist.txt"

echo "==> 5/6 пакуємо APK"
cp "$OUT/tmp/base.apk" "$OUT/tmp/app-unsigned.apk"
# Після cd шлях має бути абсолютним, інакше zip не знайде архів.
ABS_UNSIGNED="$PWD/$OUT/tmp/app-unsigned.apk"
if command -v zip >/dev/null 2>&1; then
  (cd "$OUT/dex" && zip -q -X "$ABS_UNSIGNED" classes.dex)
else
  # Запасний шлях, коли немає zip: додаємо classes.dex через zipfile.
  python3 - "$ABS_UNSIGNED" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile, shutil, os
apk, dex = sys.argv[1], sys.argv[2]
tmp = apk + ".tmp"
with zipfile.ZipFile(apk) as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename == "classes.dex":
            continue
        zout.writestr(item, zin.read(item.filename))
    zout.write(dex, "classes.dex")
shutil.move(tmp, apk)
PY
fi

echo "==> 6/6 підпис"
# Ключ лежить поза build/, бо build/ чиститься щоразу. Тимчасовий ключ з
# кожної збірки ламає оновлення: Android відхиляє APK з іншим підписом.
KS="keys/mayachok.keystore"
if [ ! -f "$KS" ]; then
  echo "ПОМИЛКА: немає $KS — створіть його один раз і збережіть у закритому сховищі."
  exit 1
fi
KS_PASS="${MAYACHOK_KS_PASS:?задайте MAYACHOK_KS_PASS у середовищі або secrets.sh}"

"$APKSIGNER" sign \
  --ks "$KS" \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" \
  --ks-key-alias mayachok \
  --min-sdk-version 21 \
  --out "$OUT/$APK_NAME" \
  "$OUT/tmp/app-unsigned.apk"

"$APKSIGNER" verify "$OUT/$APK_NAME"

echo ""
echo "==> готово: $OUT/$APK_NAME  ($(du -h "$OUT/$APK_NAME" | cut -f1))"
