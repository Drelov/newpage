#!/usr/bin/env python3
"""在 Linux 上交叉打包 Windows 程控台安装程序。"""

from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGING = Path(__file__).resolve().parent
CACHE = PACKAGING / "cache"
BUILD = PACKAGING / "build"
PAYLOAD = BUILD / "payload"
DIST = ROOT / "dist"

PYTHON_VERSION = "3.12.10"
PYTHON_ZIP_NAME = f"python-{PYTHON_VERSION}-embed-amd64.zip"
PYTHON_URL = f"https://www.python.org/ftp/python/{PYTHON_VERSION}/{PYTHON_ZIP_NAME}"
PYTHON_SHA256 = "4acbed6dd1c744b0376e3b1cf57ce906f9dc9e95e68824584c8099a63025a3c3"

WIN_GCC = "x86_64-w64-mingw32-gcc"
WIN_WINDRES = "x86_64-w64-mingw32-windres"
MAKENSIS = "makensis"


def run(args: list[str], **kwargs) -> None:
    print("+", " ".join(args), flush=True)
    subprocess.check_call(args, **kwargs)


def require(tool: str) -> None:
    if shutil.which(tool) is None:
        raise SystemExit(f"缺少 {tool}。请先安装 mingw-w64 和 nsis。")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch_python() -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    archive = CACHE / PYTHON_ZIP_NAME
    if not archive.exists():
        print(f"下载 {PYTHON_URL}", flush=True)
        urllib.request.urlretrieve(PYTHON_URL, archive)
    actual = sha256(archive)
    if actual != PYTHON_SHA256:
        archive.unlink(missing_ok=True)
        raise SystemExit(f"Python 嵌入包校验失败：{actual}")
    return archive


def copy_app(dest: Path) -> None:
    shutil.copy2(ROOT / "main.py", dest / "main.py")
    shutil.copy2(ROOT / "README.md", dest / "README.md")
    for name in ("启动程控台.vbs", "启动程控台.bat", "放置到本机.ps1"):
        raw = (ROOT / name).read_bytes().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
        if name.endswith(".ps1") and not raw.startswith(b"\xff\xfe") and not raw.startswith(b"\xef\xbb\xbf"):
            raw = b"\xef\xbb\xbf" + raw
        (dest / name).write_bytes(raw)

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
    (dest / "python312._pth").write_text("python312.zip\r\n.\r\n", encoding="ascii")


def compile_launcher(dest: Path) -> None:
    icon = BUILD / "icon.ico"
    shutil.copy2(ROOT / "assets" / "icon.ico", icon)
    rc_obj = BUILD / "launcher_res.o"
    exe = dest / "程控台.exe"
    run(
        [
            WIN_WINDRES,
            "-c",
            "65001",
            "-I",
            str(BUILD),
            "-i",
            str(PACKAGING / "launcher.rc"),
            "-o",
            str(rc_obj),
        ],
        cwd=BUILD,
    )
    run(
        [
            WIN_GCC,
            "-municode",
            "-mwindows",
            "-Os",
            "-s",
            "-static",
            str(PACKAGING / "launcher.c"),
            str(rc_obj),
            "-o",
            str(exe),
        ]
    )


def build() -> Path:
    require(WIN_GCC)
    require(WIN_WINDRES)
    require(MAKENSIS)
    archive = fetch_python()

    if BUILD.exists():
        shutil.rmtree(BUILD)
    PAYLOAD.mkdir(parents=True)
    DIST.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(archive) as zipped:
        zipped.extractall(PAYLOAD)
    copy_app(PAYLOAD)
    compile_launcher(PAYLOAD)

    outfile = DIST / "程控台.exe"
    if outfile.exists():
        outfile.unlink()
    run(
        [
            MAKENSIS,
            "-V2",
            f"-DPAYLOAD={PAYLOAD}",
            f"-DOUTFILE={outfile}",
            f"-DICON={ROOT / 'assets' / 'icon.ico'}",
            str(PACKAGING / "installer.nsi"),
        ]
    )
    if not outfile.is_file():
        raise SystemExit("NSIS 没有生成安装程序")
    print(f"已生成 {outfile} ({outfile.stat().st_size} bytes)", flush=True)
    return outfile


if __name__ == "__main__":
    os.chdir(ROOT)
    try:
        build()
    except subprocess.CalledProcessError as exc:
        raise SystemExit(exc.returncode) from exc
