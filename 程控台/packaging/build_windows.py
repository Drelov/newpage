#!/usr/bin/env python3
"""打包可在 Windows 上双击运行的程控台 zip（使用官方签名的 pythonw.exe）。"""

from __future__ import annotations

import hashlib
import os
import shutil
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGING = Path(__file__).resolve().parent
CACHE = PACKAGING / "cache"
BUILD = PACKAGING / "build"
DIST = ROOT / "dist"

PYTHON_VERSION = "3.12.10"
VARIANTS = {
    "amd64": {
        "zip_name": f"python-{PYTHON_VERSION}-embed-amd64.zip",
        "url": f"https://www.python.org/ftp/python/{PYTHON_VERSION}/python-{PYTHON_VERSION}-embed-amd64.zip",
        "sha256": "4acbed6dd1c744b0376e3b1cf57ce906f9dc9e95e68824584c8099a63025a3c3",
        "outfile": "Chengkongtai.zip",
        "pth": "python312._pth",
    },
    "win32": {
        "zip_name": f"python-{PYTHON_VERSION}-embed-win32.zip",
        "url": f"https://www.python.org/ftp/python/{PYTHON_VERSION}/python-{PYTHON_VERSION}-embed-win32.zip",
        "sha256": "084b9eb24cb848605c895d05b738fbc2572efc8b4c18c415a824065864a2b853",
        "outfile": "Chengkongtai-x86.zip",
        "pth": "python312._pth",
    },
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch(url: str, dest: Path, expected: str) -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    if not dest.exists():
        print(f"下载 {url}", flush=True)
        urllib.request.urlretrieve(url, dest)
    actual = sha256(dest)
    if actual != expected:
        dest.unlink(missing_ok=True)
        raise SystemExit(f"下载校验失败 {dest.name}：{actual}")
    return dest


def crlf(src: Path) -> bytes:
    raw = src.read_bytes().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
    if src.suffix.lower() == ".ps1" and not raw.startswith(b"\xef\xbb\xbf"):
        raw = b"\xef\xbb\xbf" + raw
    return raw


def copy_app(dest: Path) -> None:
    shutil.copy2(ROOT / "main.py", dest / "main.py")
    shutil.copy2(ROOT / "README.md", dest / "README.md")
    for name in ("启动程控台.vbs", "启动程控台.bat", "放置到本机.ps1"):
        (dest / name).write_bytes(crlf(ROOT / name))
    (dest / "Install-Local.ps1").write_bytes(crlf(ROOT / "放置到本机.ps1"))
    for name in ("Start.cmd", "Start.vbs", "Install.cmd"):
        (dest / name).write_bytes(crlf(PACKAGING / name))
    shutil.copy2(PACKAGING / "sitecustomize.py", dest / "sitecustomize.py")

    desk = dest / "desk"
    desk.mkdir()
    for path in sorted((ROOT / "desk").glob("*.py")):
        shutil.copy2(path, desk / path.name)

    static = dest / "static"
    static.mkdir()
    for path in sorted((ROOT / "static").iterdir()):
        if path.is_file():
            shutil.copy2(path, static / path.name)

    assets = dest / "assets"
    assets.mkdir()
    for name in ("icon.ico", "icon.png", "icon.svg"):
        shutil.copy2(ROOT / "assets" / name, assets / name)

    ini = "[.ShellClassInfo]\r\nIconResource=assets\\icon.ico,0\r\nInfoTip=程控台 · 应用调度\r\nConfirmFileOp=0\r\n"
    (dest / "desktop.ini").write_bytes(ini.encode("utf-16"))


def build_variant(key: str) -> Path:
    spec = VARIANTS[key]
    archive = fetch(spec["url"], CACHE / spec["zip_name"], spec["sha256"])
    payload = BUILD / key
    if payload.exists():
        shutil.rmtree(payload)
    payload.mkdir(parents=True)
    with zipfile.ZipFile(archive) as zipped:
        zipped.extractall(payload)
    copy_app(payload)
    pythonw = payload / "pythonw.exe"
    if not pythonw.is_file():
        raise SystemExit(f"{key} 缺少 pythonw.exe")
    shutil.copy2(pythonw, payload / "程控台.exe")
    shutil.copy2(pythonw, payload / "Chengkongtai.exe")
    (payload / spec["pth"]).write_text("python312.zip\r\n.\r\nimport site\r\n", encoding="ascii")

    DIST.mkdir(parents=True, exist_ok=True)
    outfile = DIST / spec["outfile"]
    if outfile.exists():
        outfile.unlink()
    with zipfile.ZipFile(outfile, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zipped:
        for path in sorted(payload.rglob("*")):
            if path.is_file():
                zipped.write(path, path.relative_to(payload).as_posix())
    print(f"已生成 {outfile} ({outfile.stat().st_size} bytes)", flush=True)
    return outfile


def build() -> None:
    if BUILD.exists():
        shutil.rmtree(BUILD)
    BUILD.mkdir(parents=True)
    build_variant("amd64")
    build_variant("win32")


if __name__ == "__main__":
    os.chdir(ROOT)
    build()
