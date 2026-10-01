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

APK ~70 КБ: жодних AndroidX, Google Play Services чи сторонніх бібліотек.
Збірка без Gradle: `aapt2` → `javac` → `d8` → `apksigner`.

## Схема даних

`gid = SHA-256(code)[0..16]` — сам код у базі не зберігається.

```sql
users  (gid, uid)  -- складений ключ: один телефон може бути в кількох групах
        name, role, last_seen, created_at
points (id, uid, gid, lat, lon, acc, bat, ts)
groups (gid, created_at)
messages(id, gid, uid, name, body, ts)  -- чат, історія тиждень
```

Складений ключ `(gid, uid)` принциповий: з глобальним `PRIMARY KEY(uid)`
пристрій міг належати лише одній групі назавжди, і при вході в другу групу
людина не з'являлася на мапі. Міграція — [`worker/migrate.sql`](worker/migrate.sql).

`role` розрізняє `tracker` (надсилає GPS) і `chat` (пише з браузера).
Без нього браузерний учасник назавжди світився б «чекаємо сигнал GPS»,
бо координат він не передає. Значення `role` при `/api/ingest` не
перезаписується — тож браузерний гість не перетвориться на трекера.

## API

Усі запити вимагають `code`. `hours` обмежено 12.

| Роут | Призначення |
|---|---|
| `POST /api/create` | створює групу, повертає код |
| `POST /api/join` | `{code, uid, name, role}` — реєстрація в групі |
| `GET /api/ingest?uid=&code=&lat=&lon=&t=&acc=&bat=&name=` | прийом точки |
| `GET /api/group?code=` | учасники групи (включно з тими, хто ще без точки) |
| `GET /api/track?code=&uid=&hours=` | історія як GeoJSON |
| `POST /api/rename` | `{code, uid, name}` — перейменування |
| `POST /api/say` | `{code, uid, name, body}` — повідомлення в чат |
| `GET /api/chat?code=&after=&limit=` | повідомлення новіші за `after` |
| `GET /api/health` | стан |

Сумісність: `?lat=&lon=&t=` без `/api/` теж приймається — старий скрипт
`gps.php` працює як задумано.

### Чат

Лише текст, без файлів. `body` обрізається до 500 символів, керуючі символи
прибираються (нові рядки лишаються), порожнє повідомлення відхиляється.
`/api/say` перевіряє, що відправник є в групі, тож чужий код не пише.

`after` — останній прочитаний `id`: клієнт тримає в пам'яті одне число
замість всього масиву, тож опитування лишається дешевим. Історія тримається
7 днів і видаляється разом з точками в `cleanup()`.

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

### Ключ підпису

Ключ лежить у `app/keys/mayachok.keystore` — поза `build/` (який чиститься
щоразу) і поза git. Пароль у `app/keys/pass.txt`, читається як
`MAYACHOK_KS_PASS`, звичайно експортується через
[`scripts/secrets.sh`](scripts/secrets.sh).

CI відновлює ключ із секретів репозиторію:

```bash
base64 -w 0 app/keys/mayachok.keystore | gh secret set MAYACHOK_KEYSTORE_B64
gh secret set MAYACHOK_KS_PASS --body '…'
```

Ключ у git не потрапляє ніколи: під ним можна підписати підробний «Маячок».
Сертифікат нинішнього ключа: SHA-256 `2031b832…`.

> `build/` чиститься на початку збірки, тож ключ не можна тримати там — інакше
> кожна збірка підписувалась би по-новому, а Android відхиляв би оновлення.
> Перевірити, що ключ стабільний: дві збірки поспіль дають однаковий
> `apksigner verify --print-certs | grep SHA-256`.

## Відомі обмеження

- GPS має бути увімкнений у системі; застосунок сам його не вмикає.
- Фонова робота тримається на foreground service — нотифікацію не можна прибрати,
  інакше Android зупинить сервіс.
- `uid` генерується як UUID і живе в налаштуваннях: перевстановлення застосунку
  створює нову особу, тож стара точка лишається на карті ще до 30 хв.
- Витрата батареї: інтервал 30 с ≈ 3%/год.
- Карта в застосунку — це `WebView` із тієї самої сторінки, що й у браузері.
  Це свідомо: Leaflet через WebView не тягне за собою нативних залежностей
  і лишається в 48 КБ. Але вона не працює офлайн і не має власних кешів
  тайлів, тож при слабкій мережі карта прогручується повільніше, ніж
  нативна.
- Чат без акаунтів: ім'я підписується самим користувачем і нічим не
  підтверджується. Секретне посилання — єдине, що тримає групу приватною.
- Усі ендпоїнти приймають код у запиті, тож він потрапляє в логи Worker.
  У базі коду немає (лише `gid`), але код не варто вважати таємницею
  проти вашого власного сервера.
