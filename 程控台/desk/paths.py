"""程序资源目录和可写目录。打包成 exe 后两者会分开。"""

from __future__ import annotations

import sys
from pathlib import Path


def resolve_roots(
    *,
    argv0_file: str,
    frozen: bool | None = None,
    meipass: str | None = None,
    executable: str | None = None,
) -> tuple[Path, Path]:
    """返回 (资源根, 工作根)。

    资源根放页面、图标；工作根放 data 台账。
    源码运行时两者相同。PyInstaller 冻结后资源在临时目录，台账跟 exe 走。
    """
    if frozen is None:
        frozen = bool(getattr(sys, "frozen", False))
    if meipass is None:
        meipass = getattr(sys, "_MEIPASS", None)
    if executable is None:
        executable = sys.executable

    if frozen:
        resource = Path(meipass) if meipass else Path(executable).parent
        return resource.resolve(), Path(executable).resolve().parent

    home = Path(argv0_file).resolve().parent
    return home, home
