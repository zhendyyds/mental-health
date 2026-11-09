#!/usr/bin/env bash
source "$(dirname -- "${BASH_SOURCE[0]}")/../mysql/compose-db.sh"
[[ $# == 1 || ( $# == 2 && $2 == --apply ) ]] || fail 'Usage: bash deployment/scripts/migrate.sh PROJECT [--apply]'
[[ $1 =~ ^[a-z0-9][a-z0-9_-]*$ ]] || fail 'Choose an explicit Compose project name.'
sql_dir="$repo_dir/mental-health-assistant-backend/db/migrations"
migrations=(
    20261102_add_missing_deleted_columns.sql
    20261106_add_ai_request_record.sql
    20261107_add_knowledge_index.sql
    20261107_allow_knowledge_qa_requests.sql
)
printf 'Existing database migration plan for project %s\n' "$1"
printf '  %s\n' "${migrations[@]}"
printf '%s\n' 'The historical soft-delete migration uses the container’s selected database instead of its fixed USE line.'
[[ $# == 2 ]] || exit 0

select_project "$1"
lock_project
require_stopped backend
require_mysql_volume
legacy_tables=$(mysql_client -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
    AND TABLE_TYPE='BASE TABLE' AND TABLE_NAME IN ('user','consultation_session','consultation_message','knowledge_category','knowledge_article','emotion_diary');")
[[ $legacy_tables == 6 ]] || fail 'The existing application schema is incomplete. Initialize a new volume with the Compose baseline.'
for migration in "${migrations[@]}"; do
    require_stopped backend
    if [[ $migration == 20261102_add_missing_deleted_columns.sql ]]; then
        sed '/^USE mental_health_assistant;[[:space:]]*$/d' "$sql_dir/$migration" | mysql_client
    else
        mysql_client < "$sql_dir/$migration"
    fi
done
printf 'Migrations complete in project %s.\n' "$project"
