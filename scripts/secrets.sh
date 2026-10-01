#!/usr/bin/env bash
#
# Читає CF_API_TOKEN і CF_ACCOUNT_ID з локального бекапу токенів
# та експортує їх у поточний шелл. Значення не друкуються.
#
# Використання:  source scripts/secrets.sh
#
# Файл НІКОЛИ не потрапляє в git — він поза репозиторієм.

TOKENS_BACKUP="${TOKENS_BACKUP:-/storage/emulated/0/Documents/tokens-backup.txt}"

if [ ! -f "$TOKENS_BACKUP" ]; then
  echo "secrets.sh: не знайдено $TOKENS_BACKUP" >&2
  echo "Вкажи шлях:  TOKENS_BACKUP=/шлях/до/файлу source scripts/secrets.sh" >&2
  return 1 2>/dev/null || exit 1
fi

CF_API_TOKEN="$(grep -m1 'API TOKEN' "$TOKENS_BACKUP" | sed 's/.*=\s*//' | tr -d '[:space:]')"
CF_ACCOUNT_ID="$(grep -m1 'ACCOUNT ID' "$TOKENS_BACKUP" | sed 's/.*=\s*//' | tr -d '[:space:]')"

if [ -z "$CF_API_TOKEN" ] || [ -z "$CF_ACCOUNT_ID" ]; then
  echo "secrets.sh: не вдалося розібрати токени" >&2
  return 1 2>/dev/null || exit 1
fi

export CF_API_TOKEN CF_ACCOUNT_ID
echo "secrets.sh: CF_API_TOKEN і CF_ACCOUNT_ID завантажено (значення приховані)"
