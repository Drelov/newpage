#!/usr/bin/env python3
"""在 Linux 上交叉打包 64 位 Windows 程控台（自解压安装程序）。"""

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
PAYLOAD = BUILD / "payload"
DIST = ROOT / "dist"

PYTHON_VERSION = "3.12.10"
PYTHON_ZIP_NAME = f"python-{PYTHON_VERSION}-embed-amd64.zip"
PYTHON_URL = f"https://www.python.org/ftp/python/{PYTHON_VERSION}/{PYTHON_ZIP_NAME}"
PYTHON_SHA256 = "4acbed6dd1c744b0376e3b1cf57ce906f9dc9e95e68824584c8099a63025a3c3"

MINIZ_URL = "https://github.com/richgel999/miniz/releases/download/3.0.2/miniz-3.0.2.zip"
MINIZ_SHA256 = "ada38db0b703a56d3dd6d57bf84a9c5d664921d870d8fea4db153979fb5332c5"

WIN_GCC = "x86_64-w64-mingw32-gcc"
WIN_WINDRES = "x86_64-w64-mingw32-windres"

PE_FLAGS = [
    "-municode",
    "-mwindows",
    "-Os",
    "-s",
    "-static",
    "-Wl,--major-os-version,6",
    "-Wl,--minor-os-version,1",
    "-Wl,--major-subsystem-version,6",
    "-Wl,--minor-subsystem-version,1",
]


def run(args: list[str], **kwargs) -> None:
    print("+", " ".join(args), flush=True)
    subprocess.check_call(args, **kwargs)


def require(tool: str) -> None:
    if shutil.which(tool) is None:
        raise SystemExit(f"缺少 {tool}。请先安装 mingw-w64。")


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


def fetch_python() -> Path:
    return fetch(PYTHON_URL, CACHE / PYTHON_ZIP_NAME, PYTHON_SHA256)


def fetch_miniz() -> Path:
    archive = fetch(MINIZ_URL, CACHE / "miniz-3.0.2.zip", MINIZ_SHA256)
    dest = BUILD / "miniz"
    dest.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as zipped:
        zipped.extract("miniz.c", dest)
        zipped.extract("miniz.h", dest)
    return dest


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


def compile_res(rc_obj: Path) -> None:
    icon = BUILD / "icon.ico"
    shutil.copy2(ROOT / "assets" / "icon.ico", icon)
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


def compile_exe(sources: list[str] | list[Path], output: Path, extra: list[str] | None = None) -> None:
    rc_obj = BUILD / "launcher_res.o"
    if not rc_obj.exists():
        compile_res(rc_obj)
    cmd = [WIN_GCC, *PE_FLAGS, *[str(src) for src in sources], str(rc_obj), "-o", str(output)]
    if extra:
        cmd[1:1] = extra
    run(cmd)


def zip_payload(src: Path, dest: Path) -> None:
    with zipfile.ZipFile(dest, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zipped:
        for path in sorted(src.rglob("*")):
            if path.is_file():
                zipped.write(path, path.relative_to(src).as_posix())


def wrap_sfx(stub: Path, archive: Path, outfile: Path) -> None:
    payload = archive.read_bytes()
    data = stub.read_bytes() + payload + len(payload).to_bytes(8, "little") + b"CKT1ZIP1"
    outfile.write_bytes(data)


def build() -> Path:
    require(WIN_GCC)
    require(WIN_WINDRES)
    python_zip = fetch_python()

    if BUILD.exists():
        shutil.rmtree(BUILD)
    PAYLOAD.mkdir(parents=True)
    DIST.mkdir(parents=True, exist_ok=True)
    miniz = fetch_miniz()

    with zipfile.ZipFile(python_zip) as zipped:
        zipped.extractall(PAYLOAD)
    copy_app(PAYLOAD)
    compile_exe([PACKAGING / "launcher.c"], PAYLOAD / "程控台.exe")

    archive = BUILD / "payload.zip"
    zip_payload(PAYLOAD, archive)

    stub = BUILD / "setup_stub.exe"
    miniz_obj = BUILD / "miniz.o"
    run(
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
    compile_exe([PACKAGING / "setup.c", miniz_obj], stub, extra=["-I", str(miniz)])

    outfile = DIST / "程控台.exe"
    wrap_sfx(stub, archive, outfile)
    ascii_copy = DIST / "Chengkongtai.exe"
    shutil.copy2(outfile, ascii_copy)
    print(f"已生成 {outfile} ({outfile.stat().st_size} bytes)", flush=True)
    print(f"已生成 {ascii_copy}", flush=True)
    return outfile


if __name__ == "__main__":
    os.chdir(ROOT)
    try:
        build()
    except subprocess.CalledProcessError as exc:
        raise SystemExit(exc.returncode) from exc
