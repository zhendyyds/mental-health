#!/usr/bin/env bash
source "$(dirname -- "${BASH_SOURCE[0]}")/../mysql/compose-db.sh"
[[ $# == 2 ]] || fail 'Usage: bash deployment/scripts/restore.sh NEW_TARGET_PROJECT BACKUP_DIRECTORY'
select_project "$1"
require_backup_tools
bundle=$2
for file in backup-info.txt schema.tsv database.sql uploads.tar.gz SHA256SUMS; do
    [[ -f $bundle/$file && ! -L $bundle/$file ]] || fail "Missing or invalid backup file: $file"
done
computed=$(bundle_digest "$bundle")
[[ $computed == "$(<"$bundle/SHA256SUMS")" ]] || fail 'Backup checksum mismatch.'
mapfile -t metadata < "$bundle/backup-info.txt"
[[ ${#metadata[@]} == 3 && ${metadata[0]} == format=healthagent-compose-data-v1 \
    && ${metadata[1]} =~ ^project=([a-z0-9][a-z0-9_-]*)$ ]] || fail 'Unsupported backup metadata.'
source_project=${BASH_REMATCH[1]}
[[ $source_project != "$project" ]] || fail 'Restore into a different, new Compose project.'
python3 "$assets_dir/check-upload-archive.py" "$bundle/uploads.tar.gz"

lock_project
require_stopped backend
require_stopped frontend
require_stopped qdrant
require_mysql_volume
require_data_schema
target_schema=$(mysql_client < "$assets_dir/schema-state.sql")
[[ $target_schema == "$(<"$bundle/schema.tsv")" ]] || fail 'Target schema does not match the backup.'
require_empty_database

vector_volume="${project}_qdrant-data"
if docker volume inspect "$vector_volume" >/dev/null 2>&1; then
    require_owned_volume "$vector_volume" qdrant-data
    vector_contents=$(uploads_container 'find /restore-vector-check -mindepth 1 -print -quit' -v "$vector_volume:/restore-vector-check:ro")
    [[ -z $vector_contents ]] || fail 'Restore requires an empty Qdrant volume.'
fi
upload_volume="${project}_uploads"
if docker volume inspect "$upload_volume" >/dev/null 2>&1; then
    require_owned_volume "$upload_volume" uploads
fi

uploads_container '
    entries=$(find /app/upload -mindepth 1 -print -quit)
    test -z "$entries" || { echo "Restore requires an empty upload volume." >&2; exit 1; }
    exec tar --no-same-owner --no-same-permissions -xzf - -C /app/upload
' < "$bundle/uploads.tar.gz"
require_stopped backend
require_empty_database
if ! {
    printf 'START TRANSACTION;\n' &&
    cat -- "$bundle/database.sql" "$assets_dir/reset-derived-index.sql" &&
    printf '\nCOMMIT;\n'
} | mysql_client >/dev/null 2>&1; then
    fail 'Database restore failed. Keep the target stopped, inspect it and retry in a new project.'
fi
printf 'Restore complete in %s. Provision the target Qdrant collection before rebuilding published article indexes.\n' "$project"
