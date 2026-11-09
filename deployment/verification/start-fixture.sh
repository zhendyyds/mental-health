#!/usr/bin/env bash
# Starts a new Compose project with a private deterministic model and no user data.
set -euo pipefail

repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
cd "$repo"
for program in docker openssl python3; do command -v "$program" >/dev/null; done
docker info --format '{{.ServerVersion}}' >/dev/null
umask 077
root="$repo/mental-health-assistant-backend/target/batch7"
mkdir -p -- "$root"
run=$(mktemp -d "$root/run.XXXXXXXX")
project="healthagent-b7-$(basename "$run" | cut -d. -f2 | tr '[:upper:]' '[:lower:]')"
port=${1:-$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')}
[[ $port =~ ^[1-9][0-9]{3,4}$ ]] && (( port >= 1024 && port <= 65535 ))
mkdir -- "$run/tls"

cat > "$run/.env" <<EOF
APP_BIND_IP=127.0.0.1
APP_PORT=$port
MYSQL_DATABASE=mental_health_assistant
MYSQL_USER=healthagent
MYSQL_ROOT_PASSWORD=$(openssl rand -hex 24)
MYSQL_PASSWORD=$(openssl rand -hex 24)
JWT_SECRET=$(openssl rand -hex 32)
AI_BASE_URL=https://ai-stub
AI_COMPLETIONS_PATH=/v1/chat/completions
AI_CHAT_MODEL=fixture-chat
AI_API_KEY=fixture-chat-key
RAG_ENABLED=true
RAG_VERSION=fixture_batch7_v1
RAG_COLLECTION=fixture_batch7
RAG_EMBEDDING_PROVIDER=openai-compatible
RAG_EMBEDDING_BASE_URL=https://ai-stub/v1
RAG_EMBEDDING_PATH=/embeddings
RAG_EMBEDDING_MODEL=fixture-embedding
RAG_EMBEDDING_DIMENSION=8
RAG_EMBEDDING_API_KEY=fixture-embedding-key
BATCH7_TLS_DIR=$run/tls
EOF
printf '%s\n' "$project" > "$run/project"
printf '%s\n' "$port" > "$run/port"
printf 'Fixture directory %s\nProject %s\nOrigin http://127.0.0.1:%s\n' "$run" "$project" "$port"

openssl req -x509 -newkey rsa:2048 -sha256 -days 7 -nodes -subj /CN=ai-stub \
    -addext 'subjectAltName=DNS:ai-stub,DNS:localhost' \
    -keyout "$run/tls/server.key" -out "$run/tls/server.pem" > "$run/tls/generation.log" 2>&1
if command -v keytool >/dev/null; then
    keytool -importcert -noprompt -storetype PKCS12 -alias fixture -storepass fixture-only \
        -file "$run/tls/server.pem" -keystore "$run/tls/ca.p12"
else
    docker run --rm --network none --entrypoint keytool --volume "$run/tls:/tls" \
        eclipse-temurin:17-jre-jammy@sha256:2a9eb226abb654c9d1f91b53cf638d20cb3abb726d1b18a67909c29515545469 \
        -importcert -noprompt -storetype PKCS12 -alias fixture -storepass fixture-only \
        -file /tls/server.pem -keystore /tls/ca.p12
fi
chmod 644 "$run/tls/ca.p12"

while IFS='=' read -r key value; do unset "$key"; done < "$run/.env"
compose=(docker compose -p "$project" --env-file "$run/.env" -f compose.yaml -f deployment/verification/compose.fixture.yaml)
"${compose[@]}" config --quiet
"${compose[@]}" up -d --wait mysql qdrant ai-stub
"${compose[@]}" run --rm qdrant-init
"${compose[@]}" up -d --build --wait frontend
"${compose[@]}" ps
printf 'Run the exercise with: python3 deployment/verification/exercise.py %q\n' "$run"
