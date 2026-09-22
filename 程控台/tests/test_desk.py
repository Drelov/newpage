import http.client
import json
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from desk.library import Library, LibraryError, target_status
from desk.server import DeskApp, serve
from desk.system import normalize_scanned_items, shortcut_is_recyclable, split_args


class LibraryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "library.json"
        self.library = Library(self.path)

    def test_add_update_delete_and_launch_record(self):
        app = self.library.add(
            {"name": "记事本", "target": r"C:\Windows\System32\notepad.exe", "category": "tools", "favorite": "false"}
        )
        self.assertFalse(app["favorite"])
        self.assertIsNone(app["exists"])
        updated = self.library.update(app["id"], {"favorite": True, "launchCount": 99})
        self.assertTrue(updated["favorite"])
        self.assertEqual(updated["launchCount"], 0)
        launched = self.library.record_launch(app["id"])
        self.assertEqual(launched["launchCount"], 1)
        self.library.data["history"] = [{"appId": app["id"], "name": "记事本", "at": "t"} for _ in range(200)]
        self.library.record_launch(app["id"])
        self.assertEqual(len(self.library.data["history"]), 200)
        self.library.delete(app["id"])
        with self.assertRaises(LibraryError):
            self.library.get(app["id"])

    def test_duplicate_and_import(self):
        self.library.add({"name": "甲", "target": r"C:\Apps\a.exe", "args": "/x"})
        with self.assertRaises(LibraryError):
            self.library.add({"name": "乙", "target": r"C:\Apps\a.exe", "args": "/x"})
        result = self.library.import_items(
            [
                {"name": "甲", "target": r"C:\Apps\a.exe", "args": "/x", "category": "media"},
                {"name": "乙", "target": r"C:\Apps\b.exe", "category": "media"},
                {"name": "", "target": r"C:\Apps\c.exe"},
            ]
        )
        self.assertEqual(result["added"], 1)
        self.assertEqual(len(result["skipped"]), 2)

    def test_corrupt_file_is_backed_up(self):
        self.path.write_text("{", encoding="utf-8")
        library = Library(self.path)
        self.assertEqual(library.snapshot()["stats"]["total"], 0)
        self.assertTrue(self.path.with_name("library.corrupt.json").exists())

    def test_target_status_does_not_flag_other_os_paths(self):
        self.assertIsNone(target_status(r"C:\Windows\System32\notepad.exe"))
        self.assertIsNone(target_status("notepad.exe"))
        self.assertFalse(target_status("/this/path/does/not/exist"))
        self.assertTrue(target_status(__file__))


class SystemTests(unittest.TestCase):
    def test_split_args(self):
        self.assertEqual(split_args(''), [])
        self.assertEqual(split_args('--title "a b"', posix=True), ["--title", "a b"])

    def test_recycle_guard(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        desk = root / "Desktop"
        evil = root / "Desktop-evil"
        desk.mkdir()
        evil.mkdir()
        good = desk / "游戏.lnk"
        good.write_text("x", encoding="utf-8")
        bad = evil / "a.lnk"
        bad.write_text("x", encoding="utf-8")
        exe = desk / "game.exe"
        exe.write_text("x", encoding="utf-8")
        self.assertTrue(shortcut_is_recyclable(str(good), [desk]))
        self.assertFalse(shortcut_is_recyclable(str(bad), [desk]))
        self.assertFalse(shortcut_is_recyclable(str(exe), [desk]))
        self.assertFalse(shortcut_is_recyclable(str(desk / ".." / "Desktop-evil" / "a.lnk"), [desk]))

    def test_scan_normalizer_skips_uninstallers(self):
        result = normalize_scanned_items(
            [
                {"name": "卸载工具", "shortcut": r"C:\Users\a\Desktop\卸载工具.lnk", "target": r"C:\un.exe"},
                {"name": "画图", "shortcut": r"C:\Users\a\Desktop\画图.lnk", "target": r"C:\Windows\System32\mspaint.exe", "note": "画图"},
            ]
        )
        self.assertEqual(len(result["items"]), 1)
        self.assertEqual(result["items"][0]["name"], "画图")
        self.assertEqual(result["items"][0]["note"], "")
        self.assertEqual(result["skipped"][0]["reason"], "已忽略卸载项")


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.desk = root / "Desktop"
        self.desk.mkdir()
        self.library = Library(root / "library.json")
        self.launched = []
        self.recycled = []
        self.app = DeskApp(
            self.library,
            token="t" * 24,
            app_root=ROOT,
            launcher=lambda app: self.launched.append(dict(app)),
            scanner=lambda: {"items": [], "skipped": [], "message": ""},
            browser=lambda kind: {"path": "", "unavailable": True},
            recycler=lambda paths: self.recycled.extend(paths) or {"recycled": list(paths), "refused": []},
            revealer=lambda path: None,
            desktop_dirs=[self.desk],
        )
        self.httpd = serve(self.app, 0)
        self.port = self.httpd.server_address[1]
        thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.httpd.server_close)
        self.addCleanup(self.httpd.shutdown)

    def request(self, method, path, body=None, token=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        if token is not None:
            req.add_header("X-Desk-Token", token)
        try:
            with urllib.request.urlopen(req, timeout=3) as response:
                raw = response.read().decode("utf-8")
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8")
            return exc.code, json.loads(raw) if raw else None

    def test_auth_and_index_token(self):
        status, _payload = self.request("GET", "/api/state")
        self.assertEqual(status, 401)
        status, payload = self.request("GET", "/api/state", token=self.app.token)
        self.assertEqual(status, 200)
        self.assertEqual(payload["stats"]["total"], 0)
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}/", timeout=3) as response:
            html = response.read().decode("utf-8")
        self.assertIn(self.app.token, html)
        self.assertNotIn("__DESK_TOKEN__", html)

    def test_launch_uses_stored_target(self):
        created = self.library.add({"name": "记事本", "target": r"C:\Windows\System32\notepad.exe", "category": "tools"})
        status, payload = self.request(
            "POST",
            f"/api/apps/{created['id']}/launch",
            body={"target": r"C:\evil.exe"},
            token=self.app.token,
        )
        self.assertEqual(status, 200)
        self.assertEqual(self.launched[0]["target"], r"C:\Windows\System32\notepad.exe")
        self.assertEqual(payload["launchCount"], 1)

    def test_import_recycle_rejects_paths_outside_desktop(self):
        inside = self.desk / "画图.lnk"
        outside = Path(self.tmp.name) / "evil.lnk"
        inside.write_text("x", encoding="utf-8")
        outside.write_text("x", encoding="utf-8")
        status, payload = self.request(
            "POST",
            "/api/import",
            body={
                "category": "media",
                "recycle": True,
                "items": [
                    {"name": "画图", "target": r"C:\Windows\System32\mspaint.exe", "sourceShortcut": str(inside)},
                    {"name": "坏", "target": r"C:\Windows\System32\cmd.exe", "sourceShortcut": str(outside)},
                ],
            },
            token=self.app.token,
        )
        self.assertEqual(status, 200)
        self.assertEqual(payload["added"], 2)
        self.assertEqual(self.recycled, [str(inside)])
        self.assertEqual(payload["refused"], [str(outside)])

    def test_static_traversal_is_blocked(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
        connection.request("GET", "/static/../main.py")
        response = connection.getresponse()
        self.assertEqual(response.status, 404)
        response.read()
        connection.close()
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}/static/app.css", timeout=3) as response:
            self.assertEqual(response.status, 200)
            self.assertIn("text/css", response.headers.get("Content-Type", ""))


if __name__ == "__main__":
    unittest.main()
