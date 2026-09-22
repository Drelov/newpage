"""Launch 程控台 when the signed pythonw executable is started with no script."""

from __future__ import annotations

import os
import runpy
import sys


def _boot() -> bool:
    if os.environ.get("DESK_SKIP_SITECUSTOMIZE") == "1":
        return False
    name = os.path.splitext(os.path.basename(sys.executable))[0].casefold()
    if name not in {"pythonw", "程控台", "chengkongtai"}:
        return False
    if len(sys.argv) > 1:
        return False
    return True


if _boot():
    here = os.path.dirname(os.path.abspath(sys.executable))
    main = os.path.join(here, "main.py")
    os.chdir(here)
    if here not in sys.path:
        sys.path.insert(0, here)
    sys.argv = [main]
    runpy.run_path(main, run_name="__main__")
    raise SystemExit(0)
