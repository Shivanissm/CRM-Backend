#!/usr/bin/env bash
# Ubuntu / EC2 only. Do not run the Windows backup-db.ps1 on this server.
#
# Daily mysqldump of Aiven MySQL -> /var/backups/crm/crm_<db>_YYYYMMDD_HHMMSS.sql.gz
# Reads /opt/crm-instagram-backend/.env  (never prints or edits it)
# Keeps 90 days. Deletes old files only after a successful dump.
#
# Cron (India time, not UTC):
# CRON_TZ=Asia/Kolkata
# 0 2 * * * ENV_FILE=/opt/crm-instagram-backend/.env /opt/crm-instagram-backend/scripts/backup-db.sh >> /var/backups/crm/backup.log 2>&1

set -euo pipefail
umask 077

ENV_FILE="${ENV_FILE:-/opt/crm-instagram-backend/.env}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/crm}"
RETENTION_DAYS="${RETENTION_DAYS:-90}"
MIN_FREE_MB="${MIN_FREE_MB:-1024}"

export TZ="${TZ:-Asia/Kolkata}"

CNF=""
TMP_OUT=""

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S %Z')  $*"
}

cleanup() {
  [ -n "$CNF" ] && rm -f "$CNF"
  [ -n "$TMP_OUT" ] && rm -f "$TMP_OUT"
}
trap cleanup EXIT

# Read KEY=value from .env. Strips Windows CR and optional quotes. Never logs the value.
get_env() {
  local key="$1"
  local line value
  line="$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | head -n 1 | tr -d '\r')" || true
  if [ -z "$line" ]; then
    printf '%s' ""
    return 0
  fi
  value="${line#*=}"
  value="${value#\"}"
  value="${value%\"}"
  value="${value#\'}"
  value="${value%\'}"
  printf '%s' "$value"
}

env_key_present() {
  grep -Eq "^[[:space:]]*${1}=" "$ENV_FILE"
}

if [ ! -f "$ENV_FILE" ]; then
  log "ERROR: env file not found: $ENV_FILE"
  exit 1
fi

mkdir -p "$BACKUP_DIR"

for key in DB_HOST DB_USER DB_PASSWORD DB_DATABASE; do
  if env_key_present "$key"; then
    log "env key present: $key"
  else
    if [ "$key" = "DB_DATABASE" ]; then
      log "env key missing: DB_DATABASE (will use defaultdb)"
    else
      log "ERROR: env key missing: $key"
      exit 1
    fi
  fi
done

DB_HOST_RAW="$(get_env DB_HOST)"
DB_HOST="${DB_HOST_RAW%%:*}"
if [ "$DB_HOST_RAW" = "$DB_HOST" ]; then
  DB_PORT="3306"
else
  DB_PORT="${DB_HOST_RAW##*:}"
fi
DB_USER="$(get_env DB_USER)"
DB_PASSWORD="$(get_env DB_PASSWORD)"
DB_NAME="$(get_env DB_DATABASE)"
if [ -z "$DB_NAME" ]; then
  DB_NAME="defaultdb"
fi

if [ -z "$DB_HOST" ] || [ -z "$DB_PORT" ] || [ -z "$DB_USER" ] || [ -z "$DB_PASSWORD" ]; then
  log "ERROR: could not parse required database settings from env file"
  exit 1
fi

FREE_MB="$(df -Pm "$BACKUP_DIR" | awk 'NR==2 {print $4}')"
if [ "${FREE_MB:-0}" -lt "$MIN_FREE_MB" ]; then
  log "ERROR: only ${FREE_MB} MB free (need ${MIN_FREE_MB} MB). Skipping dump."
  exit 1
fi

if ! command -v mysqldump >/dev/null 2>&1; then
  log "ERROR: mysqldump not found. Install mysql-client."
  exit 1
fi
if ! command -v gzip >/dev/null 2>&1; then
  log "ERROR: gzip not found."
  exit 1
fi

HELP="$(mysqldump --help 2>/dev/null || true)"

SSL_LINE="ssl=true"
if printf '%s\n' "$HELP" | grep -q -- '--ssl-mode'; then
  SSL_LINE="ssl-mode=REQUIRED"
fi

DUMP_EXTRA=()
if printf '%s\n' "$HELP" | grep -q -- '--set-gtid-purged'; then
  DUMP_EXTRA+=(--set-gtid-purged=OFF)
fi

CNF="$(mktemp "$BACKUP_DIR/.mysqldump.cnf.XXXXXX")"
chmod 600 "$CNF"
cat > "$CNF" <<EOF
[mysqldump]
host=$DB_HOST
port=$DB_PORT
user=$DB_USER
password=$DB_PASSWORD
$SSL_LINE
EOF
unset DB_PASSWORD

STAMP="$(date '+%Y%m%d_%H%M%S')"
OUT="$BACKUP_DIR/crm_${DB_NAME}_${STAMP}.sql.gz"
TMP_OUT="${OUT}.partial"

log "Starting backup of $DB_NAME on ${DB_HOST}:${DB_PORT} (SSL required)"
log "Free disk: ${FREE_MB} MB"

mysqldump --defaults-extra-file="$CNF" \
  --single-transaction \
  --default-character-set=utf8mb4 \
  --routines \
  --events \
  --triggers \
  "${DUMP_EXTRA[@]}" \
  "$DB_NAME" | gzip -c > "$TMP_OUT"

if [ ! -s "$TMP_OUT" ]; then
  log "ERROR: dump file is empty"
  exit 1
fi
if [ "$(stat -c%s "$TMP_OUT")" -lt 1000 ]; then
  log "ERROR: dump file is too small"
  exit 1
fi
if ! gzip -t "$TMP_OUT"; then
  log "ERROR: gzip -t failed; dump is corrupt"
  exit 1
fi

mv "$TMP_OUT" "$OUT"
TMP_OUT=""

rm -f "$CNF"
CNF=""

log "Backup OK: $OUT ($(du -h "$OUT" | cut -f1))"

# Retention: only reached after a successful, gzip-tested dump.
while IFS= read -r old; do
  [ -n "$old" ] || continue
  rm -f "$old"
  log "Deleted old backup: $old"
done < <(find "$BACKUP_DIR" -name 'crm_*.sql.gz' -type f -mtime +"$RETENTION_DAYS" | sort)
