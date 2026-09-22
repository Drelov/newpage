#define _UNICODE
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>

static void dirname_inplace(wchar_t *path)
{
    wchar_t *last = path;
    for (wchar_t *p = path; *p; ++p)
    {
        if (*p == L'\\' || *p == L'/')
            last = p;
    }
    if (last != path)
        *last = 0;
}

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE prev, PWSTR cmd, int show)
{
    (void)inst;
    (void)prev;
    (void)cmd;
    (void)show;

    wchar_t dir[MAX_PATH];
    DWORD n = GetModuleFileNameW(NULL, dir, MAX_PATH);
    if (n == 0 || n >= MAX_PATH)
    {
        MessageBoxW(NULL, L"无法确定程序位置。", L"程控台", MB_ICONERROR);
        return 1;
    }
    dirname_inplace(dir);
    if (!SetCurrentDirectoryW(dir))
    {
        MessageBoxW(NULL, L"无法进入程序目录。", L"程控台", MB_ICONERROR);
        return 1;
    }

    wchar_t pythonw[MAX_PATH];
    wchar_t script[MAX_PATH];
    int py_ok = _snwprintf(pythonw, MAX_PATH, L"%s\\pythonw.exe", dir);
    int script_ok = _snwprintf(script, MAX_PATH, L"%s\\main.py", dir);
    if (py_ok < 0 || py_ok >= MAX_PATH || script_ok < 0 || script_ok >= MAX_PATH)
    {
        MessageBoxW(NULL, L"程序路径过长。", L"程控台", MB_ICONERROR);
        return 1;
    }
    if (GetFileAttributesW(pythonw) == INVALID_FILE_ATTRIBUTES)
    {
        MessageBoxW(NULL, L"未找到 pythonw.exe，请使用完整的程控台文件夹。", L"程控台", MB_ICONERROR);
        return 1;
    }
    if (GetFileAttributesW(script) == INVALID_FILE_ATTRIBUTES)
    {
        MessageBoxW(NULL, L"未找到 main.py，请使用完整的程控台文件夹。", L"程控台", MB_ICONERROR);
        return 1;
    }

    SetEnvironmentVariableW(L"PYTHONUTF8", L"1");
    SetEnvironmentVariableW(L"PYTHONIOENCODING", L"utf-8");
    SetEnvironmentVariableW(L"PYTHONDONTWRITEBYTECODE", L"1");

    wchar_t cmdline[MAX_PATH * 3];
    if (_snwprintf(cmdline, MAX_PATH * 3, L"\"%s\" \"%s\"", pythonw, script) < 0)
    {
        MessageBoxW(NULL, L"无法构造启动命令。", L"程控台", MB_ICONERROR);
        return 1;
    }

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    ZeroMemory(&pi, sizeof(pi));
    si.cb = sizeof(si);

    if (!CreateProcessW(pythonw, cmdline, NULL, NULL, FALSE, 0, NULL, dir, &si, &pi))
    {
        wchar_t msg[256];
        _snwprintf(msg, 256, L"未能启动程控台（错误 %lu）。", GetLastError());
        MessageBoxW(NULL, msg, L"程控台", MB_ICONERROR);
        return 1;
    }
    CloseHandle(pi.hThread);
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD code = 0;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hProcess);
    return (int)code;
}
