"""Private deterministic OpenAI-compatible server for the fictional Compose exercise."""

import json
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


COUNTS = {"stream": 0, "qa": 0, "embedding": 0}
LOCK = threading.Lock()
MAX_BODY = 96 * 1024


def count(key):
    with LOCK:
        COUNTS[key] += 1
        return COUNTS[key]


class FixtureModel(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == "/health":
            self.json_response({"status": "ready"})
        elif self.path == "/fixture/stats":
            with LOCK:
                self.json_response(COUNTS.copy())
        else:
            self.send_error(404)

    def do_POST(self):
        try:
            payload = self.read_json()
            if self.path == "/v1/embeddings":
                self.embedding(payload)
            elif self.path == "/v1/chat/completions":
                messages = payload["messages"]
                user = next(item["content"] for item in reversed(messages) if item["role"] == "user")
                if payload.get("stream"):
                    self.stream(user, payload["model"])
                else:
                    self.completion(user, payload["model"])
            else:
                self.send_error(404)
        except (BrokenPipeError, ConnectionResetError, ssl.SSLEOFError):
            pass
        except (KeyError, TypeError, ValueError, StopIteration):
            self.send_error(400, "Invalid fixture request")

    def read_json(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            body = bytearray()
            while True:
                length = int(self.rfile.readline(128).split(b";", 1)[0], 16)
                if length == 0:
                    return json.loads(body)
                if length < 0 or len(body) + length > MAX_BODY:
                    raise ValueError("Fixture body is too large")
                body.extend(self.rfile.read(length))
                if self.rfile.read(2) != b"\r\n":
                    raise ValueError("Invalid fixture chunk")
        length = int(self.headers["Content-Length"])
        if not 1 <= length <= MAX_BODY:
            raise ValueError("Invalid fixture body length")
        return json.loads(self.rfile.read(length))

    def embedding(self, payload):
        count("embedding")
        inputs = payload["input"]
        if isinstance(inputs, str):
            inputs = [inputs]
        data = []
        for index, value in enumerate(inputs):
            vector = [0.0] * 8
            vector[0 if "蓝鹊" in value else 1] = 1.0
            data.append({"object": "embedding", "index": index, "embedding": vector})
        self.json_response({"object": "list", "data": data, "model": payload["model"],
                            "usage": {"prompt_tokens": 15, "total_tokens": 15}})

    def completion(self, user, model):
        request = json.loads(user)
        if not isinstance(request, dict) or "sources" not in request:
            self.send_error(400, "Only the fixture knowledge completion is available")
            return
        sequence = count("qa")
        if "部署超时样例" in request["question"]:
            time.sleep(7)
        selected = next((source for source in request["sources"] if "蓝鹊" in source["text"]), None)
        excerpts = [] if selected is None else [{"sourceId": selected["sourceId"], "quote": selected["text"]}]
        self.json_response({"id": f"fixture-qa-{sequence}", "object": "chat.completion", "created": int(time.time()),
                            "model": model, "choices": [{"index": 0, "finish_reason": "stop", "message": {
                                "role": "assistant", "content": json.dumps({"excerpts": excerpts}, ensure_ascii=False)}}],
                            "usage": {"prompt_tokens": 40, "completion_tokens": 20, "total_tokens": 60}})

    def stream(self, user, model):
        sequence = count("stream")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.end_headers()

        def chunk(delta, finish=None):
            content = {"id": f"fixture-stream-{sequence}", "object": "chat.completion.chunk",
                       "created": int(time.time()), "model": model,
                       "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
            self.wfile.write(f"data: {json.dumps(content, ensure_ascii=False)}\n\n".encode())
            self.wfile.flush()

        chunk({"role": "assistant", "content": "演练首段。"})
        # Spring AI 1.0.0 reads one provider chunk ahead before releasing content.
        chunk({"content": ""})
        delays = {"演练心跳": 19, "演练断线": 5, "演练停止": 30, "演练重启": 40, "演练展示": 10}
        time.sleep(next((seconds for label, seconds in delays.items() if label in user), 1))
        chunk({"content": "演练尾段。"})
        chunk({}, "stop")
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def json_response(self, data):
        encoded = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", 443), FixtureModel)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain("/tls/server.pem", "/tls/server.key")
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    server.serve_forever()
