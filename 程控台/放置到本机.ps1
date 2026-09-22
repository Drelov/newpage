# 把程控台放到 D:\Softwave\程控台。
# 若存在 D:\Softwave\新建文件夹，且目标还不存在，则先把新建文件夹改名为程控台。
$ErrorActionPreference = 'Stop'

function Normalize([string]$Path) {
  return [System.IO.Path]::GetFullPath($Path).TrimEnd('\').ToLowerInvariant()
}

$source = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not (Test-Path -LiteralPath 'D:\')) {
  throw '找不到 D: 盘。请把「程控台」文件夹手动复制到本机。'
}
if (-not (Test-Path -LiteralPath 'D:\Softwave')) {
  New-Item -ItemType Directory -Path 'D:\Softwave' | Out-Null
}

$dest = 'D:\Softwave\程控台'
$legacy = 'D:\Softwave\新建文件夹'

if ((Test-Path -LiteralPath $legacy) -and -not (Test-Path -LiteralPath $dest)) {
  $wasLegacy = (Normalize $source) -eq (Normalize $legacy)
  Rename-Item -LiteralPath $legacy -NewName '程控台'
  if ($wasLegacy) { $source = $dest }
}

if (-not (Test-Path -LiteralPath $dest)) {
  New-Item -ItemType Directory -Path $dest | Out-Null
}

if ((Normalize $source) -ne (Normalize $dest)) {
  if (-not (Get-Command robocopy.exe -ErrorAction SilentlyContinue)) {
    throw '未找到 robocopy，无法复制文件。'
  }
  & robocopy.exe $source $dest /E /NFL /NDL /NJH /NJS /XD data __pycache__ .git tests .pytest_cache /XF desktop.ini *.pyc | Out-Host
  if ($LASTEXITCODE -ge 8) { throw "复制失败（robocopy $LASTEXITCODE）" }
}

$icon = Join-Path $dest 'assets\icon.ico'
if (-not (Test-Path -LiteralPath $icon)) {
  throw "未找到图标：$icon。请确认程序文件已复制完整。"
}

$iniPath = Join-Path $dest 'desktop.ini'
if (Test-Path -LiteralPath $iniPath) {
  & attrib.exe -s -h $iniPath | Out-Null
}
$ini = "[.ShellClassInfo]`r`nIconResource=assets\icon.ico,0`r`nInfoTip=程控台 · 应用调度`r`nConfirmFileOp=0`r`n"
[System.IO.File]::WriteAllText($iniPath, $ini, [System.Text.Encoding]::Unicode)
& attrib.exe +s +h $iniPath | Out-Null
& attrib.exe +s $dest | Out-Null

Write-Host ''
Write-Host '程控台已放到:' $dest
$pythonw = Join-Path $dest 'pythonw.exe'
$main = Join-Path $dest 'main.py'
if (Test-Path -LiteralPath $pythonw) {
  Start-Process -FilePath $pythonw -ArgumentList $main -WorkingDirectory $dest
  Write-Host '已启动。以后可双击该文件夹里的 Start.cmd 或 程控台.exe。'
} else {
  Write-Host '双击其中的 Start.cmd 或「启动程控台.vbs」打开。'
}
Write-Host '如果文件夹图标没有马上变化，重新打开 D:\Softwave 即可。'

