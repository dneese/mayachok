#!/usr/bin/env bash
#
# Деплой Worker через Cloudflare REST API (wrangler не працює на Termux).
# Використовуємо multi-part завантаження — це єдиний спосіб передати біндинг D1.
#
set -euo pipefail

cd "$(dirname "$0")/.."

WORKER_NAME="${CF_WORKER_NAME:-mayachok}"
CF_API_TOKEN="${CF_API_TOKEN:-}"
CF_ACCOUNT_ID="${CF_ACCOUNT_ID:-}"
API="https://api.cloudflare.com/client/v4/accounts/$CF_ACCOUNT_ID"

if [ -z "$CF_API_TOKEN" ] || [ -z "$CF_ACCOUNT_ID" ]; then
  echo "ПОМИЛКА: потрібні CF_API_TOKEN та CF_ACCOUNT_ID" >&2
  echo "  або виконай:  source scripts/secrets.sh" >&2
  exit 1
fi

DB_ID=$(sed -n 's/^database_id = "\(.*\)"/\1/p' worker/wrangler.toml)
if [ -z "$DB_ID" ] || [ "$DB_ID" = "REPLACE_WITH_D1_DATABASE_ID" ]; then
  echo "ПОМИЛКА: у worker/wrangler.toml не задано database_id. Спершу:" >&2
  echo "  bash scripts/setup-d1.sh" >&2
  exit 1
fi

# Пакуємо worker у єдиний модуль. index.js не має жодних імпортів,
# тому достатньо перейменувати його у головний модуль.
mkdir -p .deploy
cp worker/src/index.js ".deploy/$WORKER_NAME.js"

echo "==> завантажую $WORKER_NAME (D1: $DB_ID)"

METADATA=$(cat <<JSON
{
  "main_module": "$WORKER_NAME.js",
  "compatibility_date": "2025-01-01",
  "bindings": [
    { "type": "d1", "name": "DB", "id": "$DB_ID" }
  ]
}
JSON
)

response=$(curl -sS -X PUT "$API/workers/scripts/$WORKER_NAME" \
  -H "Authorization: Bearer $CF_API_TOKEN" \
  -F "metadata=$METADATA;type=application/json" \
  -F "$WORKER_NAME.js=@.deploy/$WORKER_NAME.js;type=application/javascript+module")

if [ "$(echo "$response" | jq -r '.success // false')" != "true" ]; then
  echo "ПОМИЛКА завантаження:" >&2
  echo "$response" | jq . >&2
  exit 1
fi
echo "    завантажено"

echo "==> вмикаю workers.dev"
curl -sS -X POST "$API/workers/scripts/$WORKER_NAME/subdomain" \
  -H "Authorization: Bearer $CF_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"enabled":true}' > /dev/null

echo "==> ставлю cron (щодобове прибирання) — окремим запитом, бо multipart його не бере"
curl -sS -X PUT "$API/workers/scripts/$WORKER_NAME/schedules" \
  -H "Authorization: Bearer $CF_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '[{"cron":"17 4 * * *"}]' > /dev/null

subdomain=$(curl -sS -H "Authorization: Bearer $CF_API_TOKEN" "$API/workers/subdomain" |
  jq -r '.result.subdomain // empty')

echo ""
echo "==> готово"
echo "    https://$WORKER_NAME.$subdomain.workers.dev"
echo ""
echo "Перевірка:"
echo "  curl https://$WORKER_NAME.$subdomain.workers.dev/api/health"
echo ""
echo "Не забудь оновити API в web/app.js та app/src/.../Prefs.java, якщо адреса зміниться."
