# Common helpers for the Compose data tools. Source from a Bash entry point.
set -euo pipefail

assets_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
repo_dir=$(cd -- "$assets_dir/../.." && pwd -P)

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

select_project() {
    project=$1
    [[ $project =~ ^[a-z0-9][a-z0-9_-]*$ ]] || fail 'Choose an explicit Compose project name.'
    command -v docker >/dev/null || fail 'Docker and the Compose plugin are required.'
    compose_command=(docker compose --project-directory "$repo_dir" -f "$repo_dir/compose.yaml" -p "$project")
}

compose() { "${compose_command[@]}" "$@"; }

lock_project() {
    local lock_root="$repo_dir/mental-health-assistant-backend/target/compose-data-locks"
    mkdir -p -- "$lock_root"
    project_lock="$lock_root/$project"
    mkdir -m 700 -- "$project_lock" || fail "Another data operation holds $project_lock."
    trap 'rmdir -- "$project_lock"' EXIT
}

require_stopped() {
    local running
    running=$(docker ps -q --filter "label=com.docker.compose.project=$project" \
        --filter "label=com.docker.compose.service=$1")
    [[ -z $running ]] || fail "Stop service $1 in project $project before this operation."
}

require_mysql_volume() {
    local container mount
    container=$(compose ps -q mysql)
    [[ $container =~ ^[a-f0-9]+$ ]] || fail "Start the MySQL service in project $project first."
    mount=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Type}} {{.Name}}{{end}}{{end}}' "$container")
    [[ $mount == "volume ${project}_mysql-data" ]] || fail 'MySQL must use the selected project’s named volume.'
    require_owned_volume "${project}_mysql-data" mysql-data
}

mysql_client() {
    compose exec -T mysql sh -ec '
        export MYSQL_PWD="${MYSQL_PASSWORD:?MySQL application password is required}"
        exec mysql --no-defaults --protocol=SOCKET --user="${MYSQL_USER:?}" \
            --database="${MYSQL_DATABASE:?}" --default-character-set=utf8mb4 --binary-mode \
            --batch --skip-column-names --raw "$@"
    ' sh "$@"
}

mysql_dump() {
    local -a options
    mapfile -t options < "$assets_dir/dump-data.args"
    compose exec -T mysql sh -ec '
        export MYSQL_PWD="${MYSQL_PASSWORD:?MySQL application password is required}"
        exec mysqldump --no-defaults --protocol=SOCKET \
            --user="${MYSQL_USER:?}" "$@" "${MYSQL_DATABASE:?}"
    ' sh "${options[@]}"
}

require_data_schema() {
    local supported
    supported=$(mysql_client -e "
        SELECT IF(COUNT(*) > 0 AND COUNT(*) = SUM(TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB'), 1, 0)
        FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE();")
    [[ $supported == 1 ]] || fail 'Backup and restore require initialized InnoDB application tables.'
    supported=$(mysql_client -e "
        SELECT (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE())
            + (SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE())
            + (SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA=DATABASE());")
    [[ $supported == 0 ]] || fail 'This application backup format requires a schema without triggers, routines or events.'
}

require_empty_database() {
    local nonempty
    nonempty=$(mysql_client < "$assets_dir/assert-empty.sql")
    [[ $nonempty == 0 ]] || fail 'The restore database already contains rows.'
}

require_owned_volume() {
    local volume=$1 key=$2 owner
    owner=$(docker volume inspect --format '{{index .Labels "com.docker.compose.project"}} {{index .Labels "com.docker.compose.volume"}}' "$volume")
    [[ $owner == "$project $key" ]] || fail "Volume $volume does not belong to project $project."
}

uploads_container() {
    local script=$1
    shift
    compose run --rm --no-deps -T --entrypoint sh "$@" backend -ec "$script"
}

require_backup_tools() {
    command -v sha256sum >/dev/null || fail 'sha256sum is required.'
    command -v python3 >/dev/null || fail 'Python 3 is required to validate the upload archive.'
}

bundle_digest() {
    (cd -- "$1" && sha256sum backup-info.txt schema.tsv database.sql uploads.tar.gz)
}
