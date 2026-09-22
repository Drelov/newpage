"""本机程序台账。只保存用户登记的启动项，不负责真正启动。"""

from __future__ import annotations

import json
import os
import re
import threading
import uuid
from datetime import datetime
from pathlib import Path

CATEGORIES = [
    {"id": "office", "name": "办公"},
    {"id": "dev", "name": "研发"},
    {"id": "tools", "name": "工具"},
    {"id": "media", "name": "媒体"},
    {"id": "other", "name": "其他"},
]
CATEGORY_IDS = {item["id"] for item in CATEGORIES}
_ID = re.compile(r"^[a-f0-9]{8,32}$")
_WIN_ABS = re.compile(r"^[A-Za-z]:[\\/]")
_MAX_APPS = 500


class LibraryError(Exception):
    pass


def now_iso() -> str:
    return datetime.now().replace(microsecond=0).isoformat(timespec="seconds")


def target_status(target: str) -> bool | None:
    """True 存在，False 缺失，None 表示当前系统不核验。"""
    target = (target or "").strip()
    if not target or any(char in target for char in "\r\n\0"):
        return False
    windows_abs = bool(_WIN_ABS.match(target)) or target.startswith("\\\\")
    posix_abs = target.startswith("/")
    if windows_abs and os.name != "nt":
        return None
    if posix_abs and os.name == "nt":
        return None
    if windows_abs or posix_abs or os.path.isabs(target):
        return os.path.exists(target)
    return None


def _clip(value, limit: int) -> str:
    if value is None:
        return ""
    return str(value).replace("\r", " ").replace("\n", " ").strip()[:limit]


def _flag(value) -> bool:
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes", "on"}
    return bool(value)


def _count(value) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return 0
    return max(0, min(number, 100_000))


def _time(value) -> str | None:
    if not isinstance(value, str):
        return None
    text = value.strip()
    if not text or len(text) > 40:
        return None
    return text


class Library:
    def __init__(self, path: Path):
        self.path = Path(path)
        self._lock = threading.Lock()
        self.data = self._load()

    def _empty(self) -> dict:
        return {"version": 1, "apps": [], "history": []}

    def _load(self) -> dict:
        if not self.path.exists():
            return self._empty()
        try:
            text = self.path.read_text(encoding="utf-8")
        except OSError as exc:
            raise LibraryError(f"无法读取台账：{exc}") from exc
        try:
            raw = json.loads(text)
        except json.JSONDecodeError:
            backup = self.path.with_name("library.corrupt.json")
            try:
                backup.write_text(text, encoding="utf-8")
            except OSError:
                pass
            return self._empty()
        if not isinstance(raw, dict):
            return self._empty()
        apps = []
        for item in raw.get("apps") or []:
            if isinstance(item, dict) and item.get("id") and item.get("name") and item.get("target"):
                apps.append(item)
        history = [item for item in (raw.get("history") or []) if isinstance(item, dict)]
        return {"version": 1, "apps": apps[:_MAX_APPS], "history": history[-200:]}

    def _save_unlocked(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(".json.tmp")
        temporary.write_text(
            json.dumps(self.data, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        os.replace(temporary, self.path)

    def snapshot(self) -> dict:
        with self._lock:
            apps = [self._public(item) for item in self.data["apps"]]
            history = list(reversed(self.data["history"][-12:]))
            today = datetime.now().date().isoformat()
            launches_today = sum(1 for item in self.data["history"] if str(item.get("at", "")).startswith(today))
            last = self.data["history"][-1].get("name", "") if self.data["history"] else ""
            return {
                "categories": CATEGORIES,
                "apps": apps,
                "history": history,
                "stats": {
                    "total": len(apps),
                    "today": launches_today,
                    "favorites": sum(1 for item in apps if item.get("favorite")),
                    "last": last,
                },
            }

    def export_ledger(self) -> dict:
        with self._lock:
            return json.loads(json.dumps(self.data, ensure_ascii=False))

    def add(self, fields: dict) -> dict:
        with self._lock:
            self._ensure_capacity()
            app = self._new(self._normalize(fields))
            self._ensure_unique(app, ignore_id=None)
            self.data["apps"].append(app)
            self._save_unlocked()
            return self._public(app)

    def update(self, app_id: str, fields: dict) -> dict:
        with self._lock:
            current = self._find(app_id)
            normalized = self._normalize({**current, **fields, "id": current["id"]})
            self._ensure_unique(normalized, ignore_id=app_id)
            normalized["id"] = current["id"]
            normalized["launchCount"] = _count(current.get("launchCount"))
            normalized["lastLaunch"] = current.get("lastLaunch")
            normalized["createdAt"] = current.get("createdAt") or now_iso()
            current.clear()
            current.update(normalized)
            self._save_unlocked()
            return self._public(current)

    def delete(self, app_id: str) -> None:
        with self._lock:
            self._find(app_id)
            self.data["apps"] = [item for item in self.data["apps"] if item["id"] != app_id]
            self._save_unlocked()

    def get(self, app_id: str) -> dict:
        with self._lock:
            return self._public(self._find(app_id))

    def record_launch(self, app_id: str) -> dict:
        with self._lock:
            app = self._find(app_id)
            app["launchCount"] = _count(app.get("launchCount")) + 1
            app["lastLaunch"] = now_iso()
            self.data["history"].append(
                {"appId": app_id, "name": app["name"], "at": app["lastLaunch"]}
            )
            self.data["history"] = self.data["history"][-200:]
            self._save_unlocked()
            return self._public(app)

    def import_items(self, items: list[dict]) -> dict:
        if not isinstance(items, list):
            raise LibraryError("没有可导入的项目")
        if len(items) > 300:
            raise LibraryError("一次最多导入 300 项")
        added = 0
        skipped = []
        with self._lock:
            known = {
                self._identity(item.get("target") or "", item.get("args") or "")
                for item in self.data["apps"]
            }
            for item in items:
                label = str(item.get("name") or "") if isinstance(item, dict) else ""
                if len(self.data["apps"]) >= _MAX_APPS:
                    skipped.append({"name": label, "reason": "台账已满"})
                    continue
                if not isinstance(item, dict):
                    skipped.append({"name": label, "reason": "无效"})
                    continue
                try:
                    normalized = self._normalize(item)
                    identity = self._identity(normalized["target"], normalized["args"])
                    if identity in known:
                        raise LibraryError("已存在")
                except LibraryError as exc:
                    skipped.append({"name": label or str(item.get("name") or ""), "reason": str(exc)})
                    continue
                app = self._new(normalized)
                self.data["apps"].append(app)
                known.add(identity)
                added += 1
            if added:
                self._save_unlocked()
        return {"added": added, "skipped": skipped}

    def import_ledger(self, payload: dict) -> dict:
        if not isinstance(payload, dict) or not isinstance(payload.get("apps"), list):
            raise LibraryError("台账格式不正确")
        added = 0
        updated = 0
        with self._lock:
            by_id = {item["id"]: item for item in self.data["apps"]}
            for item in payload["apps"]:
                if not isinstance(item, dict):
                    continue
                try:
                    normalized = self._normalize(item)
                except LibraryError:
                    continue
                app_id = str(item.get("id") or "")
                if not _ID.fullmatch(app_id):
                    app_id = ""
                if app_id and app_id in by_id:
                    current = by_id[app_id]
                    current.clear()
                    current.update(normalized)
                    current["id"] = app_id
                    current["launchCount"] = _count(item.get("launchCount"))
                    current["lastLaunch"] = _time(item.get("lastLaunch"))
                    current["createdAt"] = _time(item.get("createdAt")) or now_iso()
                    updated += 1
                    continue
                if len(self.data["apps"]) >= _MAX_APPS:
                    continue
                identity = self._identity(normalized["target"], normalized["args"])
                if any(self._identity(app.get("target") or "", app.get("args") or "") == identity for app in self.data["apps"]):
                    continue
                created = self._new(normalized)
                if app_id and app_id not in by_id:
                    created["id"] = app_id
                created["launchCount"] = _count(item.get("launchCount"))
                created["lastLaunch"] = _time(item.get("lastLaunch"))
                created["createdAt"] = _time(item.get("createdAt")) or created["createdAt"]
                self.data["apps"].append(created)
                by_id[created["id"]] = created
                added += 1
            if added or updated:
                self._save_unlocked()
        return {"added": added, "updated": updated}

    def _ensure_capacity(self) -> None:
        if len(self.data["apps"]) >= _MAX_APPS:
            raise LibraryError("台账已满（500）")

    def _find(self, app_id: str) -> dict:
        for item in self.data["apps"]:
            if item.get("id") == app_id:
                return item
        raise LibraryError("找不到这个应用")

    def _new(self, normalized: dict) -> dict:
        return {
            **normalized,
            "id": uuid.uuid4().hex[:12],
            "launchCount": 0,
            "lastLaunch": None,
            "createdAt": now_iso(),
        }

    def _identity(self, target: str, args: str) -> tuple[str, str]:
        return (os.path.normcase(str(target).strip()), str(args or "").strip().casefold())

    def _ensure_unique(self, normalized: dict, ignore_id: str | None) -> None:
        identity = self._identity(normalized["target"], normalized["args"])
        for item in self.data["apps"]:
            if ignore_id and item.get("id") == ignore_id:
                continue
            if self._identity(item.get("target") or "", item.get("args") or "") == identity:
                raise LibraryError("台账里已经有相同路径和参数的程序")

    def _normalize(self, fields: dict) -> dict:
        if not isinstance(fields, dict):
            raise LibraryError("内容格式不正确")
        name = _clip(fields.get("name"), 48)
        target = _clip(fields.get("target"), 520)
        if not name:
            raise LibraryError("请填写名称")
        if not target or any(char in target for char in "\r\n\0"):
            raise LibraryError("请填写有效的程序路径")
        category = _clip(fields.get("category") or "other", 32) or "other"
        if category not in CATEGORY_IDS:
            raise LibraryError("分类无效")
        source = _clip(fields.get("sourceShortcut"), 520)
        return {
            "name": name,
            "target": target,
            "args": _clip(fields.get("args"), 300),
            "workdir": _clip(fields.get("workdir"), 520),
            "category": category,
            "note": _clip(fields.get("note"), 200),
            "favorite": _flag(fields.get("favorite")),
            "sourceShortcut": source or None,
        }

    def _public(self, app: dict) -> dict:
        return {
            "id": app.get("id"),
            "name": app.get("name"),
            "target": app.get("target"),
            "args": app.get("args") or "",
            "workdir": app.get("workdir") or "",
            "category": app.get("category"),
            "note": app.get("note") or "",
            "favorite": bool(app.get("favorite")),
            "sourceShortcut": app.get("sourceShortcut"),
            "launchCount": _count(app.get("launchCount")),
            "lastLaunch": app.get("lastLaunch"),
            "createdAt": app.get("createdAt"),
            "exists": target_status(app.get("target") or ""),
        }
