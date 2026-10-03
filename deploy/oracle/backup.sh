#!/usr/bin/env bash
# Saves the site's database in ~/sla-backups (or $BACKUP_DIR), named by the time in UTC, and keeps the newest 14.
# Cron runs it every night on the server (README, "Always on: AWS Lightsail"). The database is the one DATABASE_URL
# in the .env next to this file names; mysqldump comes from Docker's mysql image, so nothing needs installing.
#
# To restore one into a database (this replaces the tables it holds):
#   gunzip -c ~/sla-backups/sla-2026-10-02-190000.sql.gz |
#       docker run --rm -i mysql:8.4 mysql -h HOST -P PORT -u USER --password='PASSWORD' DATABASE
set -euo pipefail

here=$(dirname "$0")
dir=${BACKUP_DIR:-$HOME/sla-backups}
keep=14

decode() {  # %40 becomes @; a + stays a +, as the site reads it
    local text=${1//\\/\\\\}
    printf '%b' "${text//%/\\x}"
}

# DATABASE_URL as the site reads it (web/.../core/DatabaseUrl.java): mysql://user:password@host:port/database?...
url=$(sed -n 's/^DATABASE_URL=//p' "$here/.env" 2>/dev/null | tail -n 1 || true)
if [ -z "$url" ]; then
    echo "DATABASE_URL isn't set in $here/.env: copy .env.example to .env and fill it in." >&2
    exit 1
fi
url=${url#[\"\']}
url=${url%[\"\']}
rest=${url#*://}
authority=${rest%%/*}
database=${rest#*/}
database=${database%%\?*}
credentials=${authority%@*}
hostport=${authority##*@}
host=${hostport%:*}
port=${hostport##*:}
if [ "$port" = "$hostport" ]; then
    port=3306
fi

mkdir -p "$dir"
work=$(mktemp -d)
saved="$dir/sla-$(date -u +%Y-%m-%d-%H%M%S).sql.gz"
trap 'rm -rf "$work" "$saved.partial"' EXIT

# The password goes in an option file in a folder only this user can open, never on the command line.
printf '[client]\nuser=%s\npassword="%s"\nhost=%s\nport=%s\n' \
    "$(decode "${credentials%%:*}")" "$(decode "${credentials#*:}")" "$host" "$port" > "$work/client.cnf"

# One transaction: every table as it was at one moment, while the site keeps writing. Aiven's user may not read
# tablespaces or set GTIDs, so mysqldump leaves both out.
docker run --rm -v "$work/client.cnf:/client.cnf:ro" mysql:8.4 \
    mysqldump --defaults-extra-file=/client.cnf --single-transaction --no-tablespaces --set-gtid-purged=OFF \
    "$database" | gzip > "$saved.partial"
mv "$saved.partial" "$saved"
echo "Saved $saved"

# The names sort by time, so the oldest come first.
shopt -s nullglob
backups=("$dir"/sla-*.sql.gz)
if (( ${#backups[@]} > keep )); then
    rm -f -- "${backups[@]:0:${#backups[@]}-keep}"
fi
