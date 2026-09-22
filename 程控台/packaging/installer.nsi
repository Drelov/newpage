Unicode True
SetCompressor /SOLID lzma
RequestExecutionLevel user

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "x64.nsh"

!ifndef PAYLOAD
  !error "PAYLOAD is required"
!endif
!ifndef OUTFILE
  !error "OUTFILE is required"
!endif
!ifndef ICON
  !error "ICON is required"
!endif

Name "程控台"
BrandingText "程控台 · 应用调度"
OutFile "${OUTFILE}"
InstallDir "D:\Softwave\程控台"
AllowRootDirInstall false
ShowInstDetails show

VIProductVersion "1.1.0.0"
VIAddVersionKey /LANG=2052 "ProductName" "程控台"
VIAddVersionKey /LANG=2052 "ProductVersion" "1.1.0"
VIAddVersionKey /LANG=2052 "FileVersion" "1.1.0"
VIAddVersionKey /LANG=2052 "FileDescription" "程控台 · 应用调度"
VIAddVersionKey /LANG=2052 "OriginalFilename" "chengkongtai-setup.exe"
VIAddVersionKey /LANG=2052 "LegalCopyright" "程控台"

!define MUI_ICON "${ICON}"
!define MUI_UNICON "${ICON}"
!define MUI_ABORTWARNING
!define MUI_WELCOMEPAGE_TITLE "程控台"
!define MUI_WELCOMEPAGE_TEXT "安装本机应用程序调度控制台。$\r$\n$\r$\n默认位置：D:\\Softwave\\程控台$\r$\n若该处仍是「新建文件夹」，安装时会先改名为程控台。$\r$\n不会在桌面创建快捷方式。"
!define MUI_DIRECTORYPAGE_TEXT_TOP "程序会放到下面这个文件夹。台账写在其中的 data 目录，整个文件夹可以一起拷走。"
!define MUI_FINISHPAGE_RUN "$INSTDIR\程控台.exe"
!define MUI_FINISHPAGE_RUN_TEXT "打开程控台"
!define MUI_FINISHPAGE_NOAUTOCLOSE

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_LANGUAGE "SimpChinese"

Function .onInit
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP "程控台需要 64 位 Windows。"
    Abort
  ${EndIf}
  ${If} ${FileExists} "D:\*.*"
    ${IfNot} ${FileExists} "D:\Softwave\*.*"
      CreateDirectory "D:\Softwave"
    ${EndIf}
    ${If} ${FileExists} "D:\Softwave\新建文件夹\*.*"
    ${AndIfNot} ${FileExists} "D:\Softwave\程控台\*.*"
      Rename "D:\Softwave\新建文件夹" "D:\Softwave\程控台"
    ${EndIf}
    ${If} ${FileExists} "D:\Softwave\程控台\*.*"
      StrCpy $INSTDIR "D:\Softwave\程控台"
    ${EndIf}
  ${EndIf}
FunctionEnd

Section "程控台"
  SetOutPath $INSTDIR
  File /r "${PAYLOAD}\*.*"

  SetFileAttributes "$INSTDIR\desktop.ini" HIDDEN|SYSTEM
  SetFileAttributes "$INSTDIR" SYSTEM
SectionEnd
