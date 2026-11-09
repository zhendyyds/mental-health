#!/usr/bin/env bash
source "$(dirname -- "${BASH_SOURCE[0]}")/../mysql/compose-db.sh"
[[ $# == 2 ]] || fail 'Usage: bash deployment/scripts/backup.sh SOURCE_PROJECT NEW_BACKUP_DIRECTORY'
umask 077
select_project "$1"
require_backup_tools
lock_project
require_stopped backend
require_mysql_volume
require_owned_volume "${project}_uploads" uploads
require_data_schema

bundle=$2
mkdir -m 700 -- "$bundle"
printf 'format=healthagent-compose-data-v1\nproject=%s\ncreated_utc=%s\n' \
    "$project" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" > "$bundle/backup-info.txt"
mysql_client < "$assets_dir/schema-state.sql" > "$bundle/schema.tsv"
mysql_dump > "$bundle/database.sql"
uploads_container 'exec tar -czf - -C /app/upload .' > "$bundle/uploads.tar.gz"
python3 "$assets_dir/check-upload-archive.py" "$bundle/uploads.tar.gz"
require_stopped backend
# This is the completion marker. Failed runs leave the partial directory for inspection.
bundle_digest "$bundle" > "$bundle/SHA256SUMS"
printf 'Backup complete for %s: %s\n' "$project" "$bundle"
