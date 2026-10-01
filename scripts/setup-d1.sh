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

  # API повертає id при створенні та uuid у списку — беремо обидва варіанти.
  DB_ID=$(echo "$result" | jq -r '.result.id // .result.uuid // empty')
  if [ -z "$DB_ID" ]; then
    echo "ПОМИЛКА створення бази:" >&2
    echo "$result" | jq . >&2
    exit 1
  fi
  echo "    створено: $DB_ID"
fi

run_sql() {
  local label="$1" file="$2" sql result
  sql=$(cat "$file")
  result=$(curl -sS -X POST "$API/d1/database/$DB_ID/query" \
    -H "Authorization: Bearer $CF_API_TOKEN" \
    -H "Content-Type: application/json" \
    --data "$(jq -n --arg sql "$sql" '{sql:$sql}')")

  if [ "$(echo "$result" | jq -r '.success // false')" != "true" ]; then
    echo "ПОМИЛКА ($label):" >&2
    echo "$result" | jq . >&2
    exit 1
  fi
  echo "    $label застосовано"
}

echo "==> накатую схему"
run_sql "схема" worker/schema.sql

# Міграції йдуть після схеми: CREATE TABLE IF NOT EXISTS не змінює наявну
# таблицю, тож перехід на нові ключі робиться окремим кроком.
if [ -f worker/migrate.sql ]; then
  echo "==> застосовую міграції"
  run_sql "міграція" worker/migrate.sql
fi

echo "==> підставляю database_id у wrangler.toml"
sed -i.bak "s|^database_id = .*|database_id = \"$DB_ID\"|" worker/wrangler.toml && rm -f worker/wrangler.toml.bak
grep database_id worker/wrangler.toml

cat <<EOF

Готово. Тепер задеплой Worker:

  source scripts/secrets.sh
  bash scripts/deploy-worker.sh
EOF
