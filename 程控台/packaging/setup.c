#define _UNICODE
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "miniz.h"

#define MAGIC "CKT1ZIP1"
#define TITLE L"程控台"
#define DEST L"D:\\Softwave\\程控台"
#define LEGACY L"D:\\Softwave\\新建文件夹"

static wchar_t g_detail[1024];

static void fail(const wchar_t *text)
{
    MessageBoxW(NULL, text, TITLE, MB_ICONERROR);
}

static void set_err(const wchar_t *op, const wchar_t *path)
{
    DWORD err = GetLastError();
    if (path && path[0])
        _snwprintf(g_detail, 1024, L"%s失败。\n%s\n错误代码 %lu", op, path, err);
    else
        _snwprintf(g_detail, 1024, L"%s失败。\n错误代码 %lu", op, err);
    g_detail[1023] = 0;
}

static int utf8_to_wide(const char *src, wchar_t *dst, int dst_chars)
{
    int n = MultiByteToWideChar(CP_UTF8, 0, src, -1, dst, dst_chars);
    if (n > 0 && n <= dst_chars)
        return 1;
    n = MultiByteToWideChar(CP_ACP, 0, src, -1, dst, dst_chars);
    return n > 0 && n <= dst_chars;
}

static int join(wchar_t *out, size_t cap, const wchar_t *a, const wchar_t *b)
{
    if (_snwprintf(out, cap, L"%s\\%s", a, b) < 0)
        return 0;
    out[cap - 1] = 0;
    return 1;
}

static void make_writable(const wchar_t *path)
{
    DWORD attr = GetFileAttributesW(path);
    if (attr == INVALID_FILE_ATTRIBUTES)
        return;
    SetFileAttributesW(path, attr & ~(FILE_ATTRIBUTE_READONLY | FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_SYSTEM));
}

static int mkdir_p(const wchar_t *path)
{
    wchar_t tmp[1024];
    size_t len = wcslen(path);
    if (len == 0 || len >= 1024)
        return 0;
    memcpy(tmp, path, (len + 1) * sizeof(wchar_t));
    wchar_t *p = tmp;
    if (((tmp[0] >= L'A' && tmp[0] <= L'Z') || (tmp[0] >= L'a' && tmp[0] <= L'z')) && tmp[1] == L':' && (tmp[2] == L'\\' || tmp[2] == L'/'))
        p = tmp + 3;
    for (; *p; ++p)
    {
        if (*p == L'\\' || *p == L'/')
        {
            wchar_t save = *p;
            *p = 0;
            if (!CreateDirectoryW(tmp, NULL))
            {
                DWORD err = GetLastError();
                if (err != ERROR_ALREADY_EXISTS)
                {
                    make_writable(tmp);
                    if (!CreateDirectoryW(tmp, NULL) && GetLastError() != ERROR_ALREADY_EXISTS)
                    {
                        set_err(L"创建目录", tmp);
                        return 0;
                    }
                }
            }
            *p = save;
        }
    }
    if (!CreateDirectoryW(tmp, NULL))
    {
        DWORD err = GetLastError();
        if (err != ERROR_ALREADY_EXISTS)
        {
            make_writable(tmp);
            if (!CreateDirectoryW(tmp, NULL) && GetLastError() != ERROR_ALREADY_EXISTS)
            {
                set_err(L"创建目录", tmp);
                return 0;
            }
        }
    }
    return 1;
}

static int parent_dir(wchar_t *path)
{
    wchar_t *last = NULL;
    for (wchar_t *p = path; *p; ++p)
    {
        if (*p == L'\\' || *p == L'/')
            last = p;
    }
    if (!last)
        return 0;
    *last = 0;
    return 1;
}

static int safe_rel(const char *name)
{
    if (!name || !*name)
        return 0;
    if (name[0] == '/' || name[0] == '\\')
        return 0;
    if (((name[0] >= 'A' && name[0] <= 'Z') || (name[0] >= 'a' && name[0] <= 'z')) && name[1] == ':')
        return 0;
    const char *p = name;
    while (*p)
    {
        if (p[0] == '.' && p[1] == '.' && (p[2] == '/' || p[2] == '\\' || p[2] == 0))
            return 0;
        while (*p && *p != '/' && *p != '\\')
            ++p;
        if (*p)
            ++p;
    }
    return 1;
}

static int same_path(const wchar_t *a, const wchar_t *b)
{
    wchar_t na[1024], nb[1024];
    DWORD ga = GetFullPathNameW(a, 1024, na, NULL);
    DWORD gb = GetFullPathNameW(b, 1024, nb, NULL);
    if (!ga || !gb || ga >= 1024 || gb >= 1024)
        return 0;
    return lstrcmpiW(na, nb) == 0;
}

static int write_file(const wchar_t *path, const void *data, size_t size)
{
    make_writable(path);
    HANDLE file = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE)
    {
        DeleteFileW(path);
        file = CreateFileW(path, GENERIC_WRITE, FILE_SHARE_READ, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    }
    if (file == INVALID_HANDLE_VALUE)
    {
        set_err(L"写入文件", path);
        return 0;
    }
    const char *p = (const char *)data;
    size_t left = size;
    while (left)
    {
        DWORD chunk = left > 0x40000000u ? 0x40000000u : (DWORD)left;
        DWORD written = 0;
        if (!WriteFile(file, p, chunk, &written, NULL) || written != chunk)
        {
            CloseHandle(file);
            set_err(L"写入文件", path);
            return 0;
        }
        p += written;
        left -= written;
    }
    CloseHandle(file);
    return 1;
}

static int read_self_zip(uint8_t **out, size_t *out_size)
{
    wchar_t exe[MAX_PATH];
    DWORD n = GetModuleFileNameW(NULL, exe, MAX_PATH);
    if (!n || n >= MAX_PATH)
        return 0;
    HANDLE file = CreateFileW(exe, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE)
        return 0;
    LARGE_INTEGER sz;
    if (!GetFileSizeEx(file, &sz) || sz.QuadPart < 32)
    {
        CloseHandle(file);
        return 0;
    }
    uint8_t tail[16];
    LARGE_INTEGER pos;
    pos.QuadPart = sz.QuadPart - 16;
    if (!SetFilePointerEx(file, pos, NULL, FILE_BEGIN))
    {
        CloseHandle(file);
        return 0;
    }
    DWORD got = 0;
    if (!ReadFile(file, tail, 16, &got, NULL) || got != 16)
    {
        CloseHandle(file);
        return 0;
    }
    if (memcmp(tail + 8, MAGIC, 8) != 0)
    {
        CloseHandle(file);
        return 0;
    }
    uint64_t zip_size;
    memcpy(&zip_size, tail, 8);
    if (zip_size < 32 || zip_size > (uint64_t)sz.QuadPart - 16)
    {
        CloseHandle(file);
        return 0;
    }
    pos.QuadPart = sz.QuadPart - 16 - (LONGLONG)zip_size;
    if (!SetFilePointerEx(file, pos, NULL, FILE_BEGIN))
    {
        CloseHandle(file);
        return 0;
    }
    size_t cap = (size_t)zip_size + 1024 * 1024;
    uint8_t *buf = (uint8_t *)malloc(cap);
    if (!buf)
    {
        CloseHandle(file);
        return 0;
    }
    memset(buf, 0, cap);
    size_t left = (size_t)zip_size;
    uint8_t *walk = buf;
    while (left)
    {
        DWORD chunk = left > 0x40000000u ? 0x40000000u : (DWORD)left;
        if (!ReadFile(file, walk, chunk, &got, NULL) || got != chunk)
        {
            free(buf);
            CloseHandle(file);
            return 0;
        }
        walk += got;
        left -= got;
    }
    CloseHandle(file);
    if (buf[0] != 'P' || buf[1] != 'K')
    {
        free(buf);
        return 0;
    }
    *out = buf;
    *out_size = (size_t)zip_size;
    return 1;
}

static int extract_all(uint8_t *zip_data, size_t zip_size, const wchar_t *dest, const wchar_t *self)
{
    mz_zip_archive zip;
    memset(&zip, 0, sizeof(zip));
    if (!mz_zip_reader_init_mem(&zip, zip_data, zip_size, 0))
    {
        _snwprintf(g_detail, 1024, L"安装包解压失败（压缩数据无法识别）。");
        return 0;
    }
    int ok = 1;
    mz_uint count = mz_zip_reader_get_num_files(&zip);
    for (mz_uint i = 0; i < count; ++i)
    {
        mz_zip_archive_file_stat st;
        if (!mz_zip_reader_file_stat(&zip, i, &st) || !safe_rel(st.m_filename))
        {
            _snwprintf(g_detail, 1024, L"安装包里有无效文件名。");
            ok = 0;
            break;
        }
        wchar_t rel[512];
        if (!utf8_to_wide(st.m_filename, rel, 512))
        {
            _snwprintf(g_detail, 1024, L"无法转换文件名。");
            ok = 0;
            break;
        }
        for (wchar_t *p = rel; *p; ++p)
        {
            if (*p == L'/')
                *p = L'\\';
        }
        wchar_t full[1024];
        if (!join(full, 1024, dest, rel))
        {
            ok = 0;
            break;
        }
        int is_dir = st.m_is_directory || (rel[0] && rel[wcslen(rel) - 1] == L'\\');
        if (is_dir)
        {
            if (!mkdir_p(full))
            {
                ok = 0;
                break;
            }
            continue;
        }
        wchar_t parent[1024];
        wcsncpy(parent, full, 1023);
        parent[1023] = 0;
        if (parent_dir(parent) && parent[0] && !mkdir_p(parent))
        {
            ok = 0;
            break;
        }
        if (same_path(full, self))
            continue;
        size_t out_size = 0;
        void *payload = mz_zip_reader_extract_to_heap(&zip, i, &out_size, 0);
        if (!payload)
        {
            _snwprintf(g_detail, 1024, L"解开文件失败：%s", rel);
            ok = 0;
            break;
        }
        int written = write_file(full, payload, out_size);
        mz_free(payload);
        if (!written)
        {
            /* desktop.ini 常被系统设成隐藏/系统文件，跳过它不应让整次安装失败。 */
            const wchar_t *base = rel;
            for (const wchar_t *p = rel; *p; ++p)
            {
                if (*p == L'\\')
                    base = p + 1;
            }
            if (lstrcmpiW(base, L"desktop.ini") == 0)
                continue;
            ok = 0;
            break;
        }
    }
    mz_zip_reader_end(&zip);
    return ok;
}

static int launch(const wchar_t *dest)
{
    wchar_t pythonw[1024];
    wchar_t script[1024];
    if (!join(pythonw, 1024, dest, L"pythonw.exe") || !join(script, 1024, dest, L"main.py"))
        return 0;
    if (GetFileAttributesW(pythonw) == INVALID_FILE_ATTRIBUTES)
        return 0;
    SetEnvironmentVariableW(L"PYTHONUTF8", L"1");
    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    ZeroMemory(&pi, sizeof(pi));
    si.cb = sizeof(si);
    wchar_t cmd[2100];
    if (_snwprintf(cmd, 2100, L"\"%s\" \"%s\"", pythonw, script) < 0)
        return 0;
    if (!CreateProcessW(pythonw, cmd, NULL, NULL, FALSE, 0, NULL, dest, &si, &pi))
        return 0;
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 1;
}

static int prepare_dest(wchar_t *dest, size_t cap)
{
    if (GetDriveTypeW(L"D:\\") != DRIVE_NO_ROOT_DIR)
    {
        CreateDirectoryW(L"D:\\Softwave", NULL);
        if (GetFileAttributesW(LEGACY) != INVALID_FILE_ATTRIBUTES && GetFileAttributesW(DEST) == INVALID_FILE_ATTRIBUTES)
            MoveFileW(LEGACY, DEST);
        make_writable(DEST);
        CreateDirectoryW(DEST, NULL);
        if (GetFileAttributesW(DEST) != INVALID_FILE_ATTRIBUTES)
        {
            wcsncpy(dest, DEST, cap - 1);
            dest[cap - 1] = 0;
            return 1;
        }
    }
    wchar_t appdata[MAX_PATH];
    DWORD n = GetEnvironmentVariableW(L"LOCALAPPDATA", appdata, MAX_PATH);
    if (n && n < MAX_PATH && join(dest, cap, appdata, L"程控台") && mkdir_p(dest))
        return 1;
    fail(L"无法创建 D:\\Softwave\\程控台，也写不进本机 AppData。");
    return 0;
}

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE prev, PWSTR cmd, int show)
{
    (void)inst;
    (void)prev;
    (void)cmd;
    (void)show;

    wchar_t self[MAX_PATH];
    DWORD n = GetModuleFileNameW(NULL, self, MAX_PATH);
    if (!n || n >= MAX_PATH)
    {
        fail(L"无法确定安装程序位置。");
        return 1;
    }

    wchar_t dest[MAX_PATH];
    if (!prepare_dest(dest, MAX_PATH))
        return 1;

    uint8_t *zip_data = NULL;
    size_t zip_size = 0;
    if (!read_self_zip(&zip_data, &zip_size))
    {
        fail(L"安装包不完整。请用浏览器从 GitHub 重新下载，不要用微信或 QQ 转发 exe。");
        return 1;
    }
    int extracted = extract_all(zip_data, zip_size, dest, self);
    free(zip_data);
    if (!extracted)
    {
        if (!g_detail[0])
            _snwprintf(g_detail, 1024, L"解压到 %s 失败。", dest);
        fail(g_detail);
        return 1;
    }

    wchar_t ini[MAX_PATH];
    if (join(ini, MAX_PATH, dest, L"desktop.ini"))
    {
        make_writable(ini);
        SetFileAttributesW(ini, FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_SYSTEM);
    }

    if (!launch(dest))
    {
        wchar_t msg[768];
        _snwprintf(msg, 768, L"文件已放到：\n%s\n请双击其中的 Start.cmd 或 pythonw.exe。", dest);
        fail(msg);
        return 1;
    }
    return 0;
}
