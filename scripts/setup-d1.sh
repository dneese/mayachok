#!/usr/bin/env bash
#
# Створює D1-базу для Worker і накатує схему. Через REST API — wrangler на Termux не працює.
#
# Ключі читаються змінними CF_API_TOKEN / CF_ACCOUNT_ID.
# Щоб не тримати їх у руках, є scripts/secrets.sh — там вони беруться з бекапу.
#
set -euo pipefail

cd "$(dirname "$0")/.."

DB_NAME="${DB_NAME:-mayachok-db}"
CF_API_TOKEN="${CF_API_TOKEN:-}"
CF_ACCOUNT_ID="${CF_ACCOUNT_ID:-}"

if [ -z "$CF_API_TOKEN" ] || [ -z "$CF_ACCOUNT_ID" ]; then
  echo "ПОМИЛКА: потрібні CF_API_TOKEN та CF_ACCOUNT_ID" >&2
  echo "  або виконай:  source scripts/secrets.sh" >&2
  exit 1
fi

API="https://api.cloudflare.com/client/v4/accounts/$CF_ACCOUNT_ID"

existing=$(curl -sS -H "Authorization: Bearer $CF_API_TOKEN" "$API/d1/database" |
  jq -r --arg name "$DB_NAME" '.result[]? | select(.name==$name) | .uuid' | head -1)

if [ -n "$existing" ]; then
  DB_ID="$existing"
  echo "==> база $DB_NAME вже існує: $DB_ID"
else
  echo "==> створюю D1: $DB_NAME"
  result=$(curl -sS -X POST "$API/d1/database" \
    -H "Authorization: Bearer $CF_API_TOKEN" \
    -H "Content-Type: application/json" \
    --data "$(jq -n --arg name "$DB_NAME" '{name:$name}')")

  DB_ID=$(echo "$result" | jq -r '.result.id // empty')
  if [ -z "$DB_ID" ]; then
    echo "ПОМИЛКА створення бази:" >&2
    echo "$result" | jq . >&2
    exit 1
  fi
  echo "    створено: $DB_ID"
fi

echo "==> накатую схему"
sql=$(cat worker/schema.sql)

result=$(curl -sS -X POST "$API/d1/database/$DB_ID/query" \
  -H "Authorization: Bearer $CF_API_TOKEN" \
  -H "Content-Type: application/json" \
  --data "$(jq -n --arg sql "$sql" '{sql:$sql}')")

if [ "$(echo "$result" | jq -r '.success // false')" != "true" ]; then
  echo "ПОМИЛКА схеми:" >&2
  echo "$result" | jq . >&2
  exit 1
fi
echo "    схема застосована"

echo "==> підставляю database_id у wrangler.toml"
sed -i.bak "s|^database_id = .*|database_id = \"$DB_ID\"|" worker/wrangler.toml && rm -f worker/wrangler.toml.bak
grep database_id worker/wrangler.toml

cat <<EOF

Готово. Тепер задеплой Worker:

  source scripts/secrets.sh
  bash scripts/deploy-worker.sh
EOF
