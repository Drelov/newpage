"""程控台入口。只在本机打开一个应用调度窗口。"""

from __future__ import annotations

import atexit
import argparse
import json
import os
import secrets
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import webbrowser
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from desk import __version__
from desk.library import Library
from desk.server import DeskApp, serve
from desk.system import browse, launch_app, recycle_shortcuts, reveal, scan_desktop


def pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    return True


def health_ok(port: int) -> bool:
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/health", timeout=0.6) as response:
            return response.status == 200
    except Exception:
        return False


def find_app_browser() -> str | None:
    if sys.platform == "win32":
        roots = [
            os.environ.get("PROGRAMFILES", r"C:\Program Files"),
            os.environ.get("PROGRAMFILES(X86)", r"C:\Program Files (x86)"),
            os.environ.get("LOCALAPPDATA", ""),
        ]
        relatives = [
            r"Microsoft\Edge\Application\msedge.exe",
            r"Google\Chrome\Application\chrome.exe",
        ]
        for root in roots:
            if not root:
                continue
            for relative in relatives:
                candidate = os.path.join(root, relative)
                if os.path.isfile(candidate):
                    return candidate
        for name in ("msedge", "chrome", "msedge.exe", "chrome.exe"):
            found = shutil.which(name)
            if found:
                return found
        return None
    for name in ("microsoft-edge", "google-chrome", "google-chrome-stable", "chromium", "chromium-browser"):
        found = shutil.which(name)
        if found:
            return found
    return None


def open_window(port: int) -> None:
    url = f"http://127.0.0.1:{port}/"
    browser = find_app_browser()
    if browser:
        subprocess.Popen(
            [browser, f"--app={url}", "--window-size=1360,860", "--no-first-run", "--disable-features=TranslateUI"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        return
    webbrowser.open(url)


def make_logger(path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)

    def log(message: str) -> None:
        line = f"{datetime.now():%Y-%m-%d %H:%M:%S} {message}\n"
        try:
            with path.open("a", encoding="utf-8") as handle:
                handle.write(line)
            if path.stat().st_size > 200_000:
                path.write_bytes(path.read_bytes()[-120_000:])
        except OSError:
            return

    return log


def read_instance(path: Path) -> dict | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return data if isinstance(data, dict) else None


def bind_server(app: DeskApp, port: int):
    if port:
        return serve(app, port)
    last = None
    for candidate in range(47331, 47351):
        try:
            return serve(app, candidate)
        except OSError as exc:
            last = exc
    raise SystemExit(f"无法监听本地端口：{last}")


def main() -> None:
    parser = argparse.ArgumentParser(description="程控台")
    parser.add_argument("--serve-only", action="store_true", help="只启动本机服务，不打开窗口，也不自动退出")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--data", default="")
    args = parser.parse_args()

    os.chdir(ROOT)
    data_dir = Path(args.data).resolve() if args.data else ROOT / "data"
    data_dir.mkdir(parents=True, exist_ok=True)
    instance_path = data_dir / "instance.json"
    log = make_logger(data_dir / "desk.log")

    if not args.serve_only:
        current = read_instance(instance_path)
        if current and pid_alive(int(current.get("pid") or 0)) and health_ok(int(current.get("port") or 0)):
            open_window(int(current["port"]))
            log(f"已有实例在运行，打开窗口 {current['port']}")
            return

    token = secrets.token_urlsafe(24)
    library = Library(data_dir / "library.json")
    app = DeskApp(
        library,
        token=token,
        app_root=ROOT,
        version=__version__,
        launcher=launch_app,
        scanner=scan_desktop,
        browser=browse,
        recycler=recycle_shortcuts,
        revealer=reveal,
        log=log,
    )
    httpd = bind_server(app, args.port)
    port = httpd.server_address[1]
    instance_path.write_text(
        json.dumps({"pid": os.getpid(), "port": port}, ensure_ascii=False),
        encoding="utf-8",
    )

    def cleanup() -> None:
        current = read_instance(instance_path)
        if current and int(current.get("pid") or 0) == os.getpid():
            instance_path.unlink(missing_ok=True)

    atexit.register(cleanup)
    stop_event = threading.Event()
    closing = threading.Lock()
    closed = False

    def shutdown() -> None:
        nonlocal closed
        with closing:
            if closed:
                return
            closed = True
        stop_event.set()
        threading.Thread(target=httpd.shutdown, daemon=True).start()

    app.on_shutdown = shutdown

    def watch() -> None:
        while not stop_event.is_set():
            time.sleep(0.4)
            now = time.time()
            if not app.seen_once and now - app.started > 45:
                log("没有等到界面连接，退出")
                shutdown()
                return
            leaving = app.leaving_at
            if leaving and now - leaving > 4 and now - app.last_seen > 3:
                shutdown()
                return

    if not args.serve_only:
        threading.Thread(target=watch, daemon=True).start()
        open_window(port)
    log(f"程控台已在 http://127.0.0.1:{port}/ 监听")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        shutdown()
    finally:
        httpd.server_close()
        cleanup()


if __name__ == "__main__":
    main()
