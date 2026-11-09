"""Exercise the real application and Nginx in a new, fictional Compose project."""

import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import time
import uuid
import zlib
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener


REPO = Path(__file__).resolve().parents[2]
CHAT = "/api/psychological-chat"
ADMIN = "/api/admin/knowledge"
PASSWORD = "FictionalBatch7!2026"
FIRST = "演练首段。"
TAIL = "演练尾段。"
TITLE = "蓝鹊阅览室预约指南"
RULE = "蓝鹊阅览室每周三19:10开放预约，现场凭蓝色号牌入场。"
OPENER = build_opener(ProxyHandler({}))


def request_id():
    return str(uuid.uuid4())


def until(operation, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = operation()
        if result:
            return result
        time.sleep(0.25)
    raise AssertionError("Condition did not become true before its deadline")


def cover_png():
    width, height = 320, 160
    pixels = b"".join(b"\x00" + bytes((16, 90 + y // 3, 172 + y // 3)) * width for y in range(height))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b""))


class Stack:
    def __init__(self, run):
        self.run = run.resolve()
        root = REPO / "mental-health-assistant-backend/target/batch7"
        assert self.run.parent == root and self.run.name.startswith("run.")
        self.project = (self.run / "project").read_text().strip()
        assert re.fullmatch(r"healthagent-b7-[a-z0-9]+", self.project)
        port = int((self.run / "port").read_text())
        self.origin = f"http://127.0.0.1:{port}"
        env = self.run / ".env"
        values = env.read_text()
        assert "AI_BASE_URL=https://ai-stub\n" in values and f"APP_PORT={port}\n" in values
        keys = {line.split("=", 1)[0] for line in values.splitlines() if "=" in line}
        self.environment = {key: value for key, value in os.environ.items() if key not in keys}
        self.command = ["docker", "compose", "-p", self.project, "--env-file", str(env), "-f", "compose.yaml",
                        "-f", "deployment/verification/compose.fixture.yaml"]
        self.results = []

    def compose(self, *args, check=True):
        return subprocess.run([*self.command, *args], cwd=REPO, env=self.environment, check=check,
                              capture_output=True, text=True, timeout=240)

    def mysql(self, query):
        command = ('export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql --no-defaults --protocol=SOCKET '
                   '-u "$MYSQL_USER" -D "$MYSQL_DATABASE" --default-character-set=utf8mb4 -Nse "$1"')
        return self.compose("exec", "-T", "mysql", "sh", "-ec", command, "sh", query).stdout.strip()

    def open(self, path, method="GET", token=None, payload=None, media=None, accept=None):
        headers = {}
        if token:
            headers["token"] = token
        if accept:
            headers["Accept"] = accept
        if payload is not None:
            headers["Content-Type"] = media or "application/json"
            if not isinstance(payload, bytes):
                payload = json.dumps(payload, ensure_ascii=False).encode()
        try:
            return OPENER.open(Request(self.origin + path, data=payload, method=method, headers=headers), timeout=45)
        except HTTPError as error:
            return error

    def read(self, path, method="GET", token=None, payload=None, media=None):
        with self.open(path, method, token, payload, media) as response:
            return response.status, response.read()

    def json(self, path, method="GET", token=None, payload=None):
        status, body = self.read(path, method, token, payload)
        return status, json.loads(body)

    def ok(self, path, method="GET", token=None, payload=None):
        status, result = self.json(path, method, token, payload)
        assert status == 200 and result["code"] == "200", (path, status, result.get("code"))
        return result.get("data")

    def record(self, name, **details):
        self.results.append({"check": name, "passed": True, **details})
        (self.run / "exercise-results.json").write_text(json.dumps(self.results, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"PASS {name}", flush=True)

    def stats(self):
        script = ("import ssl,urllib.request; print(urllib.request.urlopen('https://localhost/fixture/stats',"
                  "context=ssl._create_unverified_context(),timeout=3).read().decode())")
        return json.loads(self.compose("exec", "-T", "ai-stub", "python", "-c", script).stdout)

    def login(self, username):
        return self.ok("/api/user/login", "POST", payload={"username": username, "password": PASSWORD})["token"]

    def session(self, token, message):
        session = self.ok(f"{CHAT}/session/start", "POST", token,
                          {"sessionTitle": message, "initialMessage": message})["sessionId"]
        return str(session).removeprefix("session_")

    def stream(self, token, session, message=None, client_id=None):
        path = f"{CHAT}/stream" if message else f"{CHAT}/stream/attach/{session}"
        payload = None if message is None else {"sessionId": f"session_{session}", "userMessage": message,
                                                 "clientRequestId": client_id}
        response = self.open(path, "POST", token, payload, accept="text/event-stream")
        assert response.status == 200 and response.headers.get_content_type() == "text/event-stream", (path, response.status)
        return response

    def qa(self, token, question):
        return self.json("/api/knowledge/qa", "POST", token, {"question": question, "clientRequestId": request_id()})

    def saved(self, token, session, expected):
        messages = self.ok(f"{CHAT}/sessions/{session}/messages", token=token)
        assert [item["senderType"] for item in messages] == [1, 2]
        assert messages[1]["content"] == expected


def events(response, started):
    event, data = "message", []
    for raw in response:
        line = raw.decode().rstrip("\r\n")
        elapsed_ms = int((time.monotonic() - started) * 1000)
        if line.startswith(":"):
            yield "heartbeat", None, elapsed_ms
        elif line.startswith("event:"):
            event = line[6:].lstrip()
        elif line.startswith("data:"):
            data.append(line[5:].lstrip())
        elif not line and data:
            yield event, json.loads("\n".join(data)), elapsed_ms
            event, data = "message", []


def first_piece(stream):
    for event, payload, elapsed in stream:
        if event == "message":
            assert payload["data"]["content"] == FIRST
            return elapsed
        assert event == "heartbeat", (event, payload)
    raise AssertionError("SSE stream closed before its first piece")


def terminal(stream, text):
    frames = list(stream)
    assert "".join(body["data"]["content"] for event, body, _ in frames if event == "message") == text
    assert [event for event, _, _ in frames if event in {"done", "error"}] == ["done"]
    return [ms for event, _, ms in frames if event == "heartbeat"]


def seed(stack):
    assert stack.mysql("SELECT COUNT(*) FROM `user`") == "0"
    assert stack.mysql("SELECT COUNT(*) FROM knowledge_article") == "0"
    for username, role in (("batch7_user", 1), ("batch7_admin", 1), ("batch7_rejected", 2)):
        status, payload = stack.json("/api/user/add", "POST", payload={"username": username,
                                    "email": f"{username}@example.test", "nickname": username, "userType": role,
                                    "password": PASSWORD, "confirmPassword": PASSWORD})
        assert status == 200 and payload["code"] == ("200" if role == 1 else "400"), (status, payload.get("code"))
    stack.mysql("UPDATE `user` SET user_type=2 WHERE username='batch7_admin'; "
                "INSERT INTO knowledge_category (category_name) VALUES ('虚构校园资料')")
    user, admin = stack.login("batch7_user"), stack.login("batch7_admin")
    status, response = stack.read(f"{ADMIN}/index-jobs", token=user)
    assert status == 403 or json.loads(response).get("code") == "403"
    stack.record("empty initialization, public registration and admin authorization")

    article = request_id()
    boundary = f"fixture-{uuid.uuid4().hex}"
    content = cover_png()
    parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode()
             for key, value in {"businessType": "ARTICLE", "businessId": article, "businessField": "cover"}.items()]
    parts += [f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="cover.png"\r\n'
              'Content-Type: image/png\r\n\r\n'.encode(), content, f"\r\n--{boundary}--\r\n".encode()]
    status, raw = stack.read("/api/file/upload", "POST", admin, b"".join(parts), f"multipart/form-data; boundary={boundary}")
    file = json.loads(raw)
    assert status == 200 and file["code"] == "200", (status, file.get("code"))
    upload = file["data"]["filePath"]
    category = int(stack.mysql("SELECT id FROM knowledge_category WHERE category_name='虚构校园资料'"))
    stack.ok(f"{ADMIN}/article", "POST", admin, {"id": article, "title": TITLE, "categoryId": category,
             "summary": "本地验收虚构资料", "content": f"<p>{RULE}</p>", "coverImage": upload})
    stack.ok(f"{ADMIN}/article/{article}/status", "PUT", admin, {"status": 1})
    until(lambda: stack.mysql(f"SELECT index_ready FROM knowledge_article WHERE id='{article}'") == "1")
    assert stack.read(upload) == (200, content)
    assert stack.read(f"/knowledge/article/{article}")[0] == 200
    assert stack.read("/internal/health/ready")[0] == 404
    stack.record("actual upload, public image, SPA fallback and private probes")
    return user, admin, article, upload


def exercise(stack):
    user, admin, article, upload = seed(stack)
    assert stack.read("/healthz")[0] == 200 and stack.json("/api/test")[0] == 200
    old_calls = stack.stats()["stream"]
    message = "演练心跳"
    session = stack.session(user, message)
    started = time.monotonic()
    with stack.stream(user, session, message, request_id()) as generating:
        original = events(generating, started)
        first_ms = first_piece(original)
        assert first_ms < 5000, first_ms
        attach_started = time.monotonic()
        with stack.stream(user, session) as attaching:
            attached = events(attaching, attach_started)
            assert first_piece(attached) < 5000
            with ThreadPoolExecutor(max_workers=2) as pool:
                future_a = pool.submit(terminal, original, TAIL)
                future_b = pool.submit(terminal, attached, TAIL)
                first_heartbeat, attach_heartbeat = future_a.result(), future_b.result()
    assert 10000 <= first_heartbeat[0] <= 18000 and 10000 <= attach_heartbeat[0] <= 18000
    stack.saved(user, session, FIRST + TAIL)
    assert stack.stats()["stream"] == old_calls + 1
    stack.record("Nginx first fragment, per-observer idle heartbeat and one generation", firstMs=first_ms,
                 originalHeartbeatMs=first_heartbeat[0], attachHeartbeatMs=attach_heartbeat[0])

    message, disconnected = "演练断线", request_id()
    session2 = stack.session(user, message)
    with stack.stream(user, session2, message, disconnected) as response:
        first_piece(events(response, time.monotonic()))
    until(lambda: stack.ok(f"{CHAT}/stream/requests/{disconnected}", token=user)["status"] == "SUCCEEDED")
    stack.saved(user, session2, FIRST + TAIL)
    assert stack.stats()["stream"] == old_calls + 2
    stack.record("proxy client disconnect still commits exactly one reply")

    message = "演练停止"
    session3 = stack.session(user, message)
    with stack.stream(user, session3, message, request_id()) as generating:
        original = events(generating, time.monotonic())
        first_piece(original)
        with stack.stream(user, session3) as attaching:
            attached = events(attaching, time.monotonic())
            first_piece(attached)
            stack.ok(f"{CHAT}/stream/stop", "POST", user)
            terminal(original, "")
            terminal(attached, "")
    stack.saved(user, session3, FIRST)
    assert stack.stats()["stream"] == old_calls + 3
    stack.record("explicit stop reconciles both observers and persists the partial reply")

    message, interrupted = "演练重启", request_id()
    session4 = stack.session(user, message)
    with stack.stream(user, session4, message, interrupted) as response:
        first_piece(events(response, time.monotonic()))
        stack.compose("kill", "-s", "SIGKILL", "backend")
    stack.compose("up", "-d", "--wait", "backend")
    until(lambda: stack.read("/api/test")[0] == 200)
    row = stack.ok(f"{CHAT}/stream/requests/{interrupted}", token=user)
    assert row["status"] == "UNKNOWN" and not row["saved"]
    with stack.stream(user, session4, message, interrupted) as response:
        replay = list(events(response, time.monotonic()))
    assert len(replay) == 1 and replay[0][0] == "error" and replay[0][1]["data"]["status"] == "UNKNOWN"
    assert stack.stats()["stream"] == old_calls + 4
    stack.record("backend forced restart and same-ID replay preserve the unknown outcome")

    status, grounded = stack.qa(admin, "蓝鹊阅览室何时开放预约？")
    assert status == 200 and grounded["data"]["grounded"] and grounded["data"]["citations"][0]["articleId"] == article
    started = time.monotonic()
    status, error = stack.qa(admin, "部署超时样例 蓝鹊阅览室规则")
    timeout_ms = int((time.monotonic() - started) * 1000)
    assert status == 504 and error["data"]["status"] == "UNKNOWN" and 2800 <= timeout_ms <= 7000
    stack.record("Nginx forwards the application's QA deadline and request ID", status=status, elapsedMs=timeout_ms)

    stack.compose("stop", "qdrant")
    try:
        healthy = stack.compose("exec", "-T", "backend", "wget", "-qO-", "http://127.0.0.1:8082/internal/health/ready")
        assert healthy.returncode == 0 and stack.read("/api/test")[0] == 200
        status, error = stack.qa(admin, "蓝鹊阅览室规则")
        assert status in {502, 503, 504} and error["data"]["requestId"]
    finally:
        stack.compose("up", "-d", "--wait", "qdrant")
    status, result = stack.qa(admin, "蓝鹊阅览室用什么号牌？")
    assert status == 200 and result["data"]["grounded"]
    stack.record("Qdrant fault is contained and restored without restarting the backend")

    stack.compose("stop", "mysql")
    try:
        ready = stack.compose("exec", "-T", "backend", "wget", "-qO-", "http://127.0.0.1:8082/internal/health/ready", check=False)
        live = stack.compose("exec", "-T", "backend", "wget", "-qO-", "http://127.0.0.1:8082/internal/health/live")
        assert ready.returncode != 0 and live.returncode == 0
    finally:
        stack.compose("up", "-d", "--wait", "mysql")
    until(lambda: stack.compose("exec", "-T", "backend", "wget", "-qO-", "http://127.0.0.1:8082/internal/health/ready", check=False).returncode == 0)
    stack.record("MySQL readiness drops independently of process liveness and recovers")

    stack.compose("down")
    stack.compose("up", "-d", "--wait")
    assert stack.mysql("SELECT COUNT(*) FROM `user`") == "2"
    stack.saved(user, session, FIRST + TAIL)
    assert stack.read(upload) == (200, cover_png())
    status, result = stack.qa(admin, "蓝鹊阅览室规则")
    assert status == 200 and result["data"]["citations"][0]["articleId"] == article
    stack.record("down and up preserve MySQL, Qdrant and byte-identical uploads")
    state = {"project": stack.project, "origin": stack.origin, "article": article, "session": session,
             "upload": upload, "uploadSha256": hashlib.sha256(cover_png()).hexdigest(), "interrupted": interrupted}
    (stack.run / "fixture-state.json").write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    exercise(Stack(Path(sys.argv[1])))
