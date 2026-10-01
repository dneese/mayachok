# Розробка Маячка

Технічні нотатки. Звичайному користувачеві нічого звідси не потрібно —
див. [README.md](README.md).

## Складники

| Частина | Технології | Тека |
|---|---|---|
| Android-застосунок | Java, чистий SDK, **без залежностей** | [`app/`](app) |
| API та сховище | Cloudflare Worker + D1 | [`worker/`](worker) |
| Мапа | Leaflet + OpenStreetMap, статика | [`web/`](web) |
| CI | GitHub Actions (APK + Pages) | [`.github/workflows/`](.github/workflows) |

APK ~40 КБ: жодних AndroidX, Google Play Services чи сторонніх бібліотек.
Збірка без Gradle: `aapt2` → `javac` → `d8` → `apksigner`.

## Схема даних

`gid = SHA-256(code)[0..16]` — сам код у базі не зберігається.

```sql
users  (gid, uid)  -- складений ключ: один телефон може бути в кількох групах
        name, last_seen, created_at
points (id, uid, gid, lat, lon, acc, bat, ts)
groups (gid, created_at)
```

Складений ключ `(gid, uid)` принциповий: з глобальним `PRIMARY KEY(uid)`
пристрій міг належати лише одній групі назавжди, і при вході в другу групу
людина не з'являлася на мапі. Міграція — [`worker/migrate.sql`](worker/migrate.sql).

## API

Усі запити вимагають `code`. `hours` обмежено 12.

| Роут | Призначення |
|---|---|
| `POST /api/create` | створює групу, повертає код |
| `POST /api/join` | `{code, uid, name}` — реєстрація в групі |
| `GET /api/ingest?uid=&code=&lat=&lon=&t=&acc=&bat=&name=` | прийом точки |
| `GET /api/group?code=` | учасники групи (включно з тими, хто ще без точки) |
| `GET /api/track?code=&uid=&hours=` | історія як GeoJSON |
| `POST /api/rename` | `{code, uid, name}` — перейменування |
| `GET /api/health` | стан |

Сумісність: `?lat=&lon=&t=` без `/api/` теж приймається — старий скрипт
`gps.php` працює як задумано.

## Формат коду

12 символів з алфавіту `0123456789abcdefghjkmnpqrstvwxyz` (без `i l o u`),
згруповані як `xxxx-xxxx-xxxx`. 60 біт перебору.

Нормалізація однакова в трьох місцях і має збігатися:
`Prefs.extractCode()`, `codeFromInput()` у [`web/app.js`](web/app.js),
`normalizeCode()` у [`worker/src/index.js`](worker/src/index.js) — усі
знімають дефіси й переводять у нижній регістр. Групування по чотири для
показу — `Prefs.prettyCode()` і `prettyCode()`.

## Розгортання

`wrangler` на Termux не працює, тому скрипти ходять у Cloudflare REST API напряму.

Ключі беруться з локального бекапу поза репозиторієм:

```bash
source scripts/secrets.sh     # експортує CF_API_TOKEN і CF_ACCOUNT_ID

bash scripts/setup-d1.sh      # створює D1, накатує schema.sql + migrate.sql
bash scripts/deploy-worker.sh # Worker через REST API
```

Після редеплою публічна адреса може змінитися — тоді варто перевірити, чи
вказує на актуальну адресу `Defaults.api()` у [`Prefs.java`](app/src/ru/dneese/mayachok/Prefs.java).

## Збірка APK

```bash
cd app && bash build.sh
```

Потрібні `aapt2`, `d8`, `apksigner` і `android.jar`. На Termux:

```bash
pkg install aapt2 d8 apksigner
mkdir -p ~/.cache/gps-tracker && cd ~/.cache/gps-tracker
curl -LO https://dl.google.com/android/repository/platform-35_r02.zip
unzip -o platform-35_r02.zip 'android-35/android.jar'
mv android-35/android.jar .
```

Релізи збирає GitHub Actions при пуші тега `v*`
([`apk.yml`](.github/workflows/apk.yml)). Мапу публікує
[`pages.yml`](.github/workflows/pages.yml).

> Підпис — тимчасовий ключ, який генерується при першій збірці. Для стабільних
> оновлень варто завести власний `keystore` і передати його в CI як secret.

## Відомі обмеження

- GPS має бути увімкнений у системі; застосунок сам його не вмикає.
- Фонова робота тримається на foreground service — нотифікацію не можна прибрати,
  інакше Android зупинить сервіс.
- `uid` генерується як UUID і живе в налаштуваннях: перевстановлення застосунку
  створює нову особу, тож стара точка лишається на карті ще до 30 хв.
- Витрата батареї: інтервал 30 с ≈ 3%/год.
