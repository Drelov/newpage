"""和操作系统打交道：选择文件、读取快捷方式、启动程序、移入回收站。"""

from __future__ import annotations

import base64
import json
import os
import shlex
import shutil
import subprocess
import sys
from pathlib import Path


class DeskError(Exception):
    pass


def split_args(text: str, *, posix: bool | None = None) -> list[str]:
    raw = (text or "").strip()
    if not raw:
        return []
    if any(char in raw for char in "\r\n\0"):
        raise DeskError("启动参数无效")
    if posix is None:
        posix = os.name != "nt"
    try:
        return shlex.split(raw, posix=posix)
    except ValueError as exc:
        raise DeskError("启动参数的引号没有配对") from exc


def _is_within(child: str, parent: Path) -> bool:
    try:
        child_abs = os.path.normcase(os.path.abspath(child))
        parent_abs = os.path.normcase(os.path.abspath(parent))
        return os.path.commonpath([child_abs, parent_abs]) == parent_abs
    except (OSError, ValueError):
        return False


def shortcut_is_recyclable(path: str, desks: list[Path]) -> bool:
    """只允许回收桌面目录里的 .lnk，避免误删程序本身。"""
    if not path or Path(path).suffix.lower() != ".lnk":
        return False
    return any(_is_within(path, desk) for desk in desks)


def _uninstaller(name: str) -> bool:
    lowered = name.casefold()
    return any(word in lowered for word in ("卸载", "uninstall", "uninstaller"))


def normalize_scanned_items(raw_items: list[dict]) -> dict:
    items = []
    skipped = []
    for raw in raw_items:
        if not isinstance(raw, dict):
            continue
        name = str(raw.get("name") or "").strip()
        shortcut = str(raw.get("shortcut") or "").strip()
        target = str(raw.get("target") or "").strip() or shortcut
        if _uninstaller(name):
            skipped.append({"name": name, "reason": "已忽略卸载项"})
            continue
        if not name or not target:
            skipped.append({"name": name, "reason": "无效"})
            continue
        note = str(raw.get("note") or "").strip()
        if note.casefold() == name.casefold():
            note = ""
        items.append(
            {
                "name": name[:48],
                "target": target[:520],
                "args": str(raw.get("args") or "").strip()[:300],
                "workdir": str(raw.get("workdir") or "").strip()[:520],
                "note": note[:200],
                "sourceShortcut": shortcut[:520] or None,
            }
        )
        if len(items) >= 300:
            break
    return {"items": items, "skipped": skipped}


def desktop_directories() -> list[Path]:
    if sys.platform != "win32":
        home = Path.home() / "Desktop"
        return [home] if home.is_dir() else []
    script = r"""
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$dirs = New-Object System.Collections.Generic.List[string]
$dirs.Add([Environment]::GetFolderPath('Desktop'))
$dirs.Add([Environment]::GetFolderPath('CommonDesktopDirectory'))
try {
  $reg = Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders'
  if ($reg.Desktop) { $dirs.Add([Environment]::ExpandEnvironmentVariables([string]$reg.Desktop)) }
} catch {}
$dirs | Select-Object -Unique | ConvertTo-Json -Compress
"""
    try:
        raw = _powershell(script)
        data = json.loads(raw) if raw else []
    except (DeskError, json.JSONDecodeError):
        data = []
    if isinstance(data, str):
        data = [data]
    found = []
    for item in data if isinstance(data, list) else []:
        path = Path(str(item))
        if path.is_dir() and path not in found:
            found.append(path)
    return found


def powershell_exe() -> str:
    root = os.environ.get("SystemRoot", r"C:\Windows")
    candidate = os.path.join(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
    if os.path.isfile(candidate):
        return candidate
    found = shutil.which("powershell.exe") or shutil.which("powershell") or shutil.which("pwsh")
    if not found:
        raise DeskError("未找到 PowerShell")
    return found


def _powershell(script: str, *, timeout: int = 60, interactive: bool = False) -> str:
    command = [powershell_exe(), "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script]
    if not interactive:
        command.insert(2, "-NonInteractive")
    kwargs = {}
    if sys.platform == "win32":
        kwargs["creationflags"] = 0x08000000
    try:
        completed = subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
            **kwargs,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise DeskError(f"PowerShell 调用失败：{exc}") from exc
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout or "").strip()
        raise DeskError(detail[:300] or "PowerShell 调用失败")
    return (completed.stdout or "").lstrip("\ufeff").strip()


def _parse_ps_json(text: str):
    text = (text or "").lstrip("\ufeff").strip()
    if not text:
        return []
    data = json.loads(text)
    if isinstance(data, dict):
        return [data]
    if isinstance(data, list):
        return data
    return []


def scan_desktop() -> dict:
    if sys.platform != "win32":
        return {
            "items": [],
            "skipped": [],
            "message": "桌面快捷方式导入需要在 Windows 上运行。你可以手动登记程序路径。",
        }
    script = r"""
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$sh = New-Object -ComObject WScript.Shell
$dirs = New-Object System.Collections.Generic.List[string]
$dirs.Add([Environment]::GetFolderPath('Desktop'))
$dirs.Add([Environment]::GetFolderPath('CommonDesktopDirectory'))
try {
  $reg = Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders'
  if ($reg.Desktop) { $dirs.Add([Environment]::ExpandEnvironmentVariables([string]$reg.Desktop)) }
} catch {}
$seen = @{}
$result = @()
foreach ($dir in ($dirs | Select-Object -Unique)) {
  if (-not $dir -or -not (Test-Path -LiteralPath $dir)) { continue }
  Get-ChildItem -LiteralPath $dir -Filter *.lnk -File -ErrorAction SilentlyContinue | ForEach-Object {
    $key = $_.FullName.ToLowerInvariant()
    if ($seen.ContainsKey($key)) { return }
    $seen[$key] = $true
    $s = $sh.CreateShortcut($_.FullName)
    $result += [pscustomobject]@{
      name = $_.BaseName
      shortcut = $_.FullName
      target = $s.TargetPath
      args = $s.Arguments
      workdir = $s.WorkingDirectory
      note = $s.Description
    }
  }
}
$result | ConvertTo-Json -Compress -Depth 4
"""
    try:
        raw_items = _parse_ps_json(_powershell(script, timeout=40))
    except json.JSONDecodeError as exc:
        raise DeskError("没能读完桌面快捷方式") from exc
    result = normalize_scanned_items(raw_items)
    result["message"] = ""
    return result


def resolve_shortcut(path: str) -> dict:
    name = Path(path).stem
    fallback = {"name": name, "target": path, "args": "", "workdir": "", "note": ""}
    if sys.platform != "win32" or not path.lower().endswith(".lnk"):
        return fallback
    escaped = path.replace("'", "''")
    script = f"""
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$sh = New-Object -ComObject WScript.Shell
$s = $sh.CreateShortcut('{escaped}')
[pscustomobject]@{{
  name = [IO.Path]::GetFileNameWithoutExtension('{escaped}')
  target = $s.TargetPath
  args = $s.Arguments
  workdir = $s.WorkingDirectory
  note = $s.Description
}} | ConvertTo-Json -Compress
"""
    try:
        data = json.loads(_powershell(script))
    except (DeskError, json.JSONDecodeError):
        return fallback
    if not isinstance(data, dict):
        return fallback
    target = str(data.get("target") or "").strip() or path
    return {
        "name": str(data.get("name") or name).strip()[:48] or name,
        "target": target[:520],
        "args": str(data.get("args") or "").strip()[:300],
        "workdir": str(data.get("workdir") or "").strip()[:520],
        "note": str(data.get("note") or "").strip()[:200],
    }


def browse(kind: str) -> dict:
    kind = "folder" if kind == "folder" else "file"
    if sys.platform == "win32":
        path = _browse_windows(kind)
        if not path:
            return {"path": ""}
        if kind == "file" and path.lower().endswith(".lnk"):
            return {"path": path, **resolve_shortcut(path)}
        return {"path": path, "target": path}
    if not shutil.which("zenity"):
        return {"path": "", "unavailable": True, "message": "当前环境没有文件对话框，请直接粘贴路径。"}
    path = _browse_zenity(kind)
    return {"path": path or "", "target": path or ""}


def _browse_windows(kind: str) -> str:
    if kind == "folder":
        script = r"""
Add-Type -AssemblyName System.Windows.Forms
$owner = New-Object System.Windows.Forms.Form
$owner.TopMost = $true
$owner.ShowInTaskbar = $false
$dialog = New-Object System.Windows.Forms.FolderBrowserDialog
$dialog.Description = '选择工作目录'
if ($dialog.ShowDialog($owner) -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $dialog.SelectedPath }
$owner.Dispose()
"""
    else:
        script = r"""
Add-Type -AssemblyName System.Windows.Forms
$owner = New-Object System.Windows.Forms.Form
$owner.TopMost = $true
$owner.ShowInTaskbar = $false
$dialog = New-Object System.Windows.Forms.OpenFileDialog
$dialog.Title = '选择要登记的程序'
$dialog.Filter = '程序与快捷方式|*.exe;*.lnk;*.bat;*.cmd;*.url|所有文件|*.*'
$dialog.CheckFileExists = $true
if ($dialog.ShowDialog($owner) -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $dialog.FileName }
$owner.Dispose()
"""
    try:
        return _clean_dialog(_powershell(script, timeout=300, interactive=True))
    except DeskError:
        return ""


def _clean_dialog(text: str) -> str:
    lines = [line.strip() for line in (text or "").splitlines() if line.strip()]
    return lines[-1] if lines else ""


def _browse_zenity(kind: str) -> str:
    command = ["zenity", "--file-selection", "--title=选择程序"]
    if kind == "folder":
        command.append("--directory")
    try:
        completed = subprocess.run(command, capture_output=True, text=True, check=False)
    except OSError:
        return ""
    if completed.returncode != 0:
        return ""
    return (completed.stdout or "").strip()


def launch_app(app: dict) -> None:
    target = str(app.get("target") or "").strip()
    if not target or any(char in target for char in "\r\n\0"):
        raise DeskError("路径无效")
    args = split_args(str(app.get("args") or ""))
    workdir = str(app.get("workdir") or "").strip()
    if workdir and not os.path.isdir(workdir):
        workdir = ""
    suffix = Path(target).suffix.lower()
    if sys.platform == "win32":
        if suffix in {".lnk", ".url", ".bat", ".cmd"} and not args:
            try:
                os.startfile(target)  # type: ignore[attr-defined]
                return
            except OSError as exc:
                raise DeskError(f"无法启动：{exc.strerror or exc}") from exc
        try:
            subprocess.Popen(
                [target, *args],
                cwd=workdir or None,
                creationflags=0x00000008 | 0x00000200,
                close_fds=True,
            )
            return
        except OSError as exc:
            if os.path.exists(target):
                try:
                    os.startfile(target)  # type: ignore[attr-defined]
                    return
                except OSError:
                    pass
            raise DeskError(f"无法启动：{exc.strerror or exc}") from exc
    if os.path.isfile(target) and os.access(target, os.X_OK) and suffix not in {".txt", ".png", ".pdf", ".json"}:
        subprocess.Popen([target, *args], cwd=workdir or None, start_new_session=True)
        return
    opener = shutil.which("xdg-open")
    if opener and os.path.exists(target):
        subprocess.Popen([opener, target], start_new_session=True)
        return
    raise DeskError("无法启动，路径不存在或没有执行权限")


def reveal(path: str) -> None:
    if not path or any(char in path for char in "\r\n\0"):
        raise DeskError("路径无效")
    folder = path if os.path.isdir(path) else os.path.dirname(path)
    if not folder or not os.path.isdir(folder):
        raise DeskError("所在位置不存在")
    if sys.platform == "win32":
        if os.path.isfile(path):
            subprocess.Popen(["explorer.exe", "/select,", os.path.normpath(path)], creationflags=0x00000008)
        else:
            os.startfile(folder)  # type: ignore[attr-defined]
        return
    opener = shutil.which("xdg-open")
    if not opener:
        raise DeskError("无法打开所在位置")
    subprocess.Popen([opener, folder], start_new_session=True)


def recycle_shortcuts(paths: list[str]) -> dict:
    if sys.platform != "win32":
        raise DeskError("只有 Windows 可以把快捷方式移入回收站")
    if not paths:
        return {"recycled": [], "refused": []}
    encoded = base64.b64encode(json.dumps(list(paths), ensure_ascii=False).encode("utf-8")).decode("ascii")
    script = f"""
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Add-Type -AssemblyName Microsoft.VisualBasic
$json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('{encoded}'))
$paths = ConvertFrom-Json $json
foreach ($p in @($paths)) {{
  [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile([string]$p, 'OnlyErrorDialogs', 'SendToRecycleBin')
}}
"""
    _powershell(script, timeout=40)
    return {"recycled": list(paths), "refused": []}
