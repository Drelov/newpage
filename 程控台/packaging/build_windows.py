#!/usr/bin/env python3
"""打包可在 Windows 上双击运行的程控台 zip（使用官方签名的 pythonw.exe）。"""

from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGING = Path(__file__).resolve().parent
CACHE = PACKAGING / "cache"
BUILD = PACKAGING / "build"
DIST = ROOT / "dist"

MINIZ_URL = "https://github.com/richgel999/miniz/releases/download/3.0.2/miniz-3.0.2.zip"
MINIZ_SHA256 = "ada38db0b703a56d3dd6d57bf84a9c5d664921d870d8fea4db153979fb5332c5"
WIN_GCC = "x86_64-w64-mingw32-gcc"
WIN_WINDRES = "x86_64-w64-mingw32-windres"
PE_FLAGS = [
    "-municode",
    "-mwindows",
    "-Os",
    "-static",
    "-finput-charset=UTF-8",
    "-Wl,--major-os-version,6",
    "-Wl,--minor-os-version,1",
    "-Wl,--major-subsystem-version,6",
    "-Wl,--minor-subsystem-version,1",
]

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


def fetch_miniz() -> Path:
    archive = fetch(MINIZ_URL, CACHE / "miniz-3.0.2.zip", MINIZ_SHA256)
    dest = BUILD / "miniz"
    dest.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as zipped:
        zipped.extract("miniz.c", dest)
        zipped.extract("miniz.h", dest)
    return dest


def wrap_sfx(payload_dir: Path) -> Path:
    if shutil.which(WIN_GCC) is None or shutil.which(WIN_WINDRES) is None:
        print("未找到 mingw，跳过 exe 安装包", flush=True)
        return payload_dir
    miniz = fetch_miniz()
    icon = BUILD / "icon.ico"
    shutil.copy2(ROOT / "assets" / "icon.ico", icon)
    rc_obj = BUILD / "setup_res.o"
    subprocess.check_call(
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
    miniz_obj = BUILD / "miniz.o"
    subprocess.check_call(
        [
            WIN_GCC,
            "-c",
            "-Os",
            "-DMINIZ_NO_STDIO",
            "-DMINIZ_NO_ARCHIVE_WRITING_APIS",
            "-I",
            str(miniz),
            str(miniz / "miniz.c"),
            "-o",
            str(miniz_obj),
        ]
    )
    stub = BUILD / "setup_stub.exe"
    subprocess.check_call(
        [
            WIN_GCC,
            *PE_FLAGS,
            "-I",
            str(miniz),
            str(PACKAGING / "setup.c"),
            str(miniz_obj),
            str(rc_obj),
            "-o",
            str(stub),
        ]
    )
    archive = BUILD / "sfx-payload.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zipped:
        for path in sorted(payload_dir.rglob("*")):
            if path.is_file():
                zipped.write(path, path.relative_to(payload_dir).as_posix())
    payload = archive.read_bytes()
    outfile = DIST / "Chengkongtai.exe"
    outfile.write_bytes(stub.read_bytes() + payload + len(payload).to_bytes(8, "little") + b"CKT1ZIP1")
    shutil.copy2(outfile, DIST / "程控台.exe")
    print(f"已生成 {outfile} ({outfile.stat().st_size} bytes)", flush=True)
    return outfile


def build() -> None:
    if BUILD.exists():
        shutil.rmtree(BUILD)
    BUILD.mkdir(parents=True)
    build_variant("amd64")
    build_variant("win32")
    wrap_sfx(BUILD / "amd64")


if __name__ == "__main__":
    os.chdir(ROOT)
    build()
