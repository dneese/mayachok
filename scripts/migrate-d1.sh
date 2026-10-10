#!/usr/bin/env bash
#
# Накат міграції D1 на існуючу базу. Через REST API — wrangler на Termux
# не запускається (аборигенний бінарник під glibc).
#
# Використання:
#   source scripts/secrets.sh
#   bash scripts/migrate-d1.sh worker/migrate-1.7.sql
#
# Спочатку робиться резервна копія таблиці users: міграція 1.7 не змінює
# жодних рядків (лише прибирає колонку й таблиці), але копія коштує
# один запит і дає спокій.
set -euo pipefail

cd "$(dirname "$0")/.."

SQL_FILE="${1:-worker/migrate-1.7.sql}"
DB_NAME="${DB_NAME:-mayachok-db}"
CF_API_TOKEN="${CF_API_TOKEN:-}"
CF_ACCOUNT_ID="${CF_ACCOUNT_ID:-}"

if [ -z "$CF_API_TOKEN" ] || [ -z "$CF_ACCOUNT_ID" ]; then
  echo "ПОМИЛКА: потрібні CF_API_TOKEN та CF_ACCOUNT_ID" >&2
  echo "  або виконай:  source scripts/secrets.sh" >&2
  exit 1
fi

if [ ! -f "$SQL_FILE" ]; then
  echo "ПОМИЛКА: не знайдено файл міграції: $SQL_FILE" >&2
  exit 1
fi

API="https://api.cloudflare.com/client/v4/accounts/$CF_ACCOUNT_ID"

DB_ID=$(curl -sS -H "Authorization: Bearer $CF_API_TOKEN" "$API/d1/database" |
  jq -r --arg name "$DB_NAME" '.result[]? | select(.name==$name) | .uuid' | head -1)

if [ -z "$DB_ID" ]; then
  echo "ПОМИЛКА: база $DB_NAME не знайдена" >&2
  exit 1
fi
echo "==> база $DB_NAME ($DB_ID)"

# --- резервна копія перед будь-якими змінами ---
# Копія НЕ має потрапляти в git: там uid, імена та координати реальних
# людей. Тому пишемо поза репозиторієм за замовчуванням.
BACKUP_DIR="${BACKUP_DIR:-$HOME/mayachok-backups}"
mkdir -p "$BACKUP_DIR"
BACKUP="$BACKUP_DIR/backup-users-$(date +%Y%m%d-%H%M%S).json"
echo "==> резервна копія users -> $BACKUP"
curl -sS -X POST "$API/d1/database/$DB_ID/query" \
  -H "Authorization: Bearer $CF_API_TOKEN" \
  -H "Content-Type: application/json" \
  --data "$(jq -n '{sql:"SELECT * FROM users"}')" |
  jq -r '.result[0].results // []' > "$BACKUP"
echo "    $(jq 'length' "$BACKUP") рядків збережено"

# --- сама міграція ---
echo "==> накатую $SQL_FILE"
result=$(curl -sS -X POST "$API/d1/database/$DB_ID/query" \
  -H "Authorization: Bearer $CF_API_TOKEN" \
  -H "Content-Type: application/json" \
  --data "$(jq -n --rawfile sql "$SQL_FILE" '{sql:$sql}')")

if [ "$(echo "$result" | jq -r '.success // false')" != "true" ]; then
  echo "ПОМИЛКА міграції (база не змінена):" >&2
  echo "$result" | jq . >&2
  exit 1
fi

# D1 повертає результат кожного виразу окремо; нас цікавить, чи всі пройшли.
failed=$(echo "$result" | jq '[.result[]? | select(.success == false)] | length')
if [ "$failed" != "0" ]; then
  echo "ПОМИЛКА: частина виразів не виконалася" >&2
  echo "$result" | jq '.result[] | select(.success == false)' >&2
  exit 1
fi

echo "==> перевіряю результат"
curl -sS -X POST "$API/d1/database/$DB_ID/query" \
  -H "Authorization: Bearer $CF_API_TOKEN" \
  -H "Content-Type: application/json" \
  --data "$(jq -n '{sql:"SELECT name FROM sqlite_master WHERE type=\"table\""}')" |
  jq -r '"    таблиці: " + ([.result[0].results[]?.name] | join(", "))'

echo
echo "Готово. Резервна копія: $BACKUP"
echo "Не забудь задеплоїти Worker, інакше стара версія вже не знає role/messages:"
echo "  bash scripts/deploy-worker.sh"