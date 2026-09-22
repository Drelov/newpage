"""只监听本机的调度服务。页面和接口都带一次性令牌。"""

from __future__ import annotations

import json
import secrets
import time
import traceback
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from desk.library import LibraryError
from desk.system import (
    DeskError,
    browse,
    desktop_directories,
    launch_app,
    recycle_shortcuts,
    reveal,
    scan_desktop,
    shortcut_is_recyclable,
)

MIME = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".ico": "image/x-icon",
}


class DeskApp:
    def __init__(self, library, token: str, app_root: Path, **hooks):
        self.library = library
        self.token = token
        self.app_root = Path(app_root)
        self.version = hooks.get("version") or "1.0.0"
        self.launcher = hooks.get("launcher") or launch_app
        self.scanner = hooks.get("scanner") or scan_desktop
        self.browser = hooks.get("browser") or browse
        self.recycler = hooks.get("recycler") or recycle_shortcuts
        self.revealer = hooks.get("revealer") or reveal
        self.on_shutdown = hooks.get("on_shutdown")
        self.log = hooks.get("log") or (lambda _message: None)
        self.desktop_dirs = hooks.get("desktop_dirs")
        self.started = time.time()
        self.last_seen = self.started
        self.leaving_at = None
        self.seen_once = False

    def note_ping(self) -> None:
        self.last_seen = time.time()
        self.leaving_at = None
        self.seen_once = True

    def note_bye(self) -> None:
        self.leaving_at = time.time()

    def desks(self):
        if self.desktop_dirs is not None:
            return self.desktop_dirs
        return desktop_directories()


class DeskServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def serve(app: DeskApp, port: int = 0) -> DeskServer:
    httpd = DeskServer(("127.0.0.1", port), Handler)
    httpd.app = app  # type: ignore[attr-defined]
    httpd.timeout = 0.5
    return httpd


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        return

    def do_GET(self):
        self._dispatch("GET")

    def do_POST(self):
        self._dispatch("POST")

    def do_PATCH(self):
        self._dispatch("PATCH")

    def do_DELETE(self):
        self._dispatch("DELETE")

    def _app(self) -> DeskApp:
        return self.server.app  # type: ignore[attr-defined]

    def _dispatch(self, method: str) -> None:
        if self.client_address[0] not in {"127.0.0.1", "::1"}:
            self._send(403, {"error": "仅允许本机访问"})
            return
        host = (self.headers.get("Host") or "").split(":")[0].strip("[]").lower()
        if host not in {"127.0.0.1", "localhost"}:
            self._send(403, {"error": "仅允许本机访问"})
            return
        parsed = urllib.parse.urlparse(self.path)
        path = urllib.parse.unquote(parsed.path)
        try:
            if path == "/favicon.ico" and method == "GET":
                self._file(self._app().app_root / "assets" / "icon.ico")
                return
            if path in {"/", "/index.html"} and method == "GET":
                self._index()
                return
            if path.startswith("/static/") and method == "GET":
                self._static(self._app().app_root / "static", path[len("/static/") :])
                return
            if path.startswith("/assets/") and method == "GET":
                self._static(self._app().app_root / "assets", path[len("/assets/") :])
                return
            if path.startswith("/api/"):
                self._api(method, path)
                return
        except (LibraryError, DeskError) as exc:
            self._send(400, {"error": str(exc)})
            return
        except Exception:
            self._app().log(traceback.format_exc())
            self._send(500, {"error": "内部错误"})
            return
        self._send(404, {"error": "未找到"})

    def _api(self, method: str, path: str) -> None:
        import re

        if path == "/api/health" and method == "GET":
            self._send(200, {"ok": True, "version": self._app().version})
            return
        if not self._token_ok(path):
            return
        app = self._app()
        if path == "/api/ping" and method == "POST":
            app.note_ping()
            self._send(200, {"ok": True})
            return
        if path == "/api/bye" and method == "POST":
            app.note_bye()
            self._send(200, {"ok": True})
            return
        if path == "/api/shutdown" and method == "POST":
            self._send(200, {"ok": True})
            if app.on_shutdown:
                app.on_shutdown()
            return
        if path == "/api/state" and method == "GET":
            self._send(200, {**app.library.snapshot(), "version": app.version})
            return
        if path == "/api/ledger" and method == "GET":
            self._send(200, app.library.export_ledger())
            return
        if path == "/api/apps" and method == "POST":
            self._run(lambda: app.library.add(self._body()))
            return
        if path == "/api/scan-desktop" and method == "POST":
            self._run(app.scanner)
            return
        if path == "/api/import" and method == "POST":
            self._run(lambda: self._import(self._body()))
            return
        if path == "/api/browse" and method == "POST":
            self._run(lambda: app.browser((self._body() or {}).get("kind") or "file"))
            return
        if path == "/api/ledger/import" and method == "POST":
            self._run(lambda: app.library.import_ledger(self._body()))
            return
        matched = re.fullmatch(r"/api/apps/([a-f0-9]{8,32})", path)
        if matched and method == "PATCH":
            self._run(lambda: app.library.update(matched.group(1), self._body()))
            return
        if matched and method == "DELETE":
            self._run(lambda: app.library.delete(matched.group(1)) or {"ok": True})
            return
        action = re.fullmatch(r"/api/apps/([a-f0-9]{8,32})/(launch|reveal)", path)
        if action and method == "POST":
            app_id, name = action.group(1), action.group(2)

            def go():
                item = app.library.get(app_id)
                if name == "launch":
                    app.launcher(item)
                    return app.library.record_launch(app_id)
                app.revealer(item["target"])
                return {"ok": True}

            self._run(go)
            return
        self._send(404, {"error": "未找到"})

    def _import(self, body: dict) -> dict:
        items = body.get("items")
        if not isinstance(items, list):
            raise LibraryError("没有可导入的项目")
        category = str(body.get("category") or "other")
        cleaned = []
        for item in items:
            if not isinstance(item, dict):
                continue
            cleaned.append(
                {
                    "name": item.get("name"),
                    "target": item.get("target"),
                    "args": item.get("args"),
                    "workdir": item.get("workdir"),
                    "note": item.get("note"),
                    "category": item.get("category") or category,
                    "favorite": bool(item.get("favorite")),
                    "sourceShortcut": item.get("sourceShortcut"),
                }
            )
        result = self._app().library.import_items(cleaned)
        recycled: list[str] = []
        refused: list[str] = []
        if body.get("recycle"):
            safe = []
            seen = set()
            for item in cleaned:
                raw = str(item.get("sourceShortcut") or "").strip()
                if not raw or raw in seen:
                    continue
                seen.add(raw)
                if shortcut_is_recyclable(raw, self._app().desks()):
                    safe.append(raw)
                else:
                    refused.append(raw)
            if safe:
                try:
                    outcome = self._app().recycler(safe) or {}
                except DeskError as exc:
                    refused.extend(safe)
                    result["recycleError"] = str(exc)
                else:
                    recycled = list(outcome.get("recycled") or [])
                    refused.extend(outcome.get("refused") or [])
        return {**result, "recycled": recycled, "refused": refused}

    def _run(self, fn) -> None:
        try:
            payload = fn()
        except (LibraryError, DeskError) as exc:
            self._send(400, {"error": str(exc)})
            return
        except Exception:
            self._app().log(traceback.format_exc())
            self._send(500, {"error": "内部错误"})
            return
        self._send(200, {"ok": True} if payload is None else payload)

    def _token_ok(self, path: str) -> bool:
        supplied = self.headers.get("X-Desk-Token") or ""
        if not supplied and path == "/api/bye":
            parsed = urllib.parse.urlparse(self.path)
            supplied = (urllib.parse.parse_qs(parsed.query).get("t") or [""])[0]
        expected = self._app().token
        if not supplied or len(supplied) != len(expected) or not secrets.compare_digest(supplied, expected):
            self._send(401, {"error": "未授权"})
            return False
        return True

    def _body(self) -> dict:
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError as exc:
            raise LibraryError("内容格式不正确") from exc
        if length < 0 or length > 2_000_000:
            raise LibraryError("内容过大")
        if length == 0:
            return {}
        raw = self.rfile.read(length)
        try:
            data = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise LibraryError("内容格式不正确") from exc
        if not isinstance(data, dict):
            raise LibraryError("内容格式不正确")
        return data

    def _index(self) -> None:
        file = self._app().app_root / "static" / "index.html"
        text = file.read_text(encoding="utf-8").replace("__DESK_TOKEN__", self._app().token)
        self._send(200, text, "text/html; charset=utf-8")

    def _static(self, root: Path, rel: str) -> None:
        path = self._safe(root, rel)
        if path is None:
            self._send(404, {"error": "未找到"})
            return
        self._file(path)

    def _file(self, path: Path) -> None:
        if not path.is_file():
            self._send(404, {"error": "未找到"})
            return
        mime = MIME.get(path.suffix.lower(), "application/octet-stream")
        self._send(200, path.read_bytes(), mime)

    def _safe(self, root: Path, rel: str) -> Path | None:
        if not rel or rel.startswith(("/", "\\")):
            return None
        if ".." in Path(rel).parts:
            return None
        root_resolved = root.resolve()
        path = (root / rel).resolve()
        try:
            path.relative_to(root_resolved)
        except ValueError:
            return None
        if not path.is_file():
            return None
        return path

    def _send(self, code: int, payload, content_type: str = "application/json; charset=utf-8") -> None:
        if isinstance(payload, (dict, list)):
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        elif isinstance(payload, str):
            data = payload.encode("utf-8")
        else:
            data = payload
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(data)
