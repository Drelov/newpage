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

static void fail(const wchar_t *text)
{
    MessageBoxW(NULL, text, TITLE, MB_ICONERROR);
}

static int utf8_to_wide(const char *src, wchar_t *dst, int dst_chars)
{
    int n = MultiByteToWideChar(CP_UTF8, 0, src, -1, dst, dst_chars);
    if (n <= 0 || n > dst_chars)
        return 0;
    return 1;
}

static int join(wchar_t *out, size_t cap, const wchar_t *a, const wchar_t *b)
{
    if (_snwprintf(out, cap, L"%s\\%s", a, b) < 0)
        return 0;
    out[cap - 1] = 0;
    return 1;
}

static int mkdir_p(const wchar_t *path)
{
    wchar_t tmp[1024];
    size_t len = wcslen(path);
    if (len == 0 || len >= 1024)
        return 0;
    memcpy(tmp, path, (len + 1) * sizeof(wchar_t));
    for (wchar_t *p = tmp + 3; *p; ++p)
    {
        if (*p == L'\\' || *p == L'/')
        {
            wchar_t save = *p;
            *p = 0;
            if (!CreateDirectoryW(tmp, NULL))
            {
                DWORD err = GetLastError();
                if (err != ERROR_ALREADY_EXISTS)
                    return 0;
            }
            *p = save;
        }
    }
    if (!CreateDirectoryW(tmp, NULL))
    {
        DWORD err = GetLastError();
        if (err != ERROR_ALREADY_EXISTS)
            return 0;
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
    HANDLE file = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE)
        return 0;
    const char *p = (const char *)data;
    size_t left = size;
    while (left)
    {
        DWORD chunk = left > 0x40000000u ? 0x40000000u : (DWORD)left;
        DWORD written = 0;
        if (!WriteFile(file, p, chunk, &written, NULL) || written != chunk)
        {
            CloseHandle(file);
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
    uint8_t *buf = (uint8_t *)malloc((size_t)zip_size);
    if (!buf)
    {
        CloseHandle(file);
        return 0;
    }
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

static int extract_all(const uint8_t *zip_data, size_t zip_size, const wchar_t *dest, const wchar_t *self)
{
    mz_zip_archive zip;
    memset(&zip, 0, sizeof(zip));
    if (!mz_zip_reader_init_mem(&zip, zip_data, zip_size, 0))
        return 0;
    int ok = 1;
    mz_uint count = mz_zip_reader_get_num_files(&zip);
    for (mz_uint i = 0; i < count; ++i)
    {
        mz_zip_archive_file_stat st;
        if (!mz_zip_reader_file_stat(&zip, i, &st) || !safe_rel(st.m_filename))
        {
            ok = 0;
            break;
        }
        wchar_t rel[512];
        if (!utf8_to_wide(st.m_filename, rel, 512))
        {
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
        if (parent_dir(parent) && !mkdir_p(parent))
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
            ok = 0;
            break;
        }
        int written = write_file(full, payload, out_size);
        mz_free(payload);
        if (!written)
        {
            ok = 0;
            break;
        }
    }
    mz_zip_reader_end(&zip);
    return ok;
}

static int launch(const wchar_t *dest)
{
    wchar_t exe[1024];
    if (!join(exe, 1024, dest, L"程控台.exe"))
        return 0;
    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    ZeroMemory(&pi, sizeof(pi));
    si.cb = sizeof(si);
    wchar_t cmd[1024];
    if (_snwprintf(cmd, 1024, L"\"%s\"", exe) < 0)
        return 0;
    if (!CreateProcessW(exe, cmd, NULL, NULL, FALSE, 0, NULL, dest, &si, &pi))
        return 0;
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 1;
}

static int prepare_dest(wchar_t *dest, size_t cap)
{
    if (GetDriveTypeW(L"D:\\") == DRIVE_NO_ROOT_DIR)
    {
        fail(L"找不到 D: 盘。请把这个文件拷到有 D 盘的电脑后再打开。");
        return 0;
    }
    CreateDirectoryW(L"D:\\Softwave", NULL);
    if (GetFileAttributesW(LEGACY) != INVALID_FILE_ATTRIBUTES && GetFileAttributesW(DEST) == INVALID_FILE_ATTRIBUTES)
        MoveFileW(LEGACY, DEST);
    CreateDirectoryW(DEST, NULL);
    if (GetFileAttributesW(DEST) == INVALID_FILE_ATTRIBUTES)
    {
        fail(L"无法创建 D:\\Softwave\\程控台。");
        return 0;
    }
    wcsncpy(dest, DEST, cap - 1);
    dest[cap - 1] = 0;
    return 1;
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
        fail(L"解压到 D:\\Softwave\\程控台 失败。");
        return 1;
    }

    wchar_t ini[MAX_PATH];
    if (join(ini, MAX_PATH, dest, L"desktop.ini"))
        SetFileAttributesW(ini, FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_SYSTEM);
    SetFileAttributesW(dest, FILE_ATTRIBUTE_SYSTEM);

    if (!launch(dest))
    {
        fail(L"文件已放到 D:\\Softwave\\程控台，但没能自动打开。请双击其中的程控台.exe。");
        return 1;
    }
    return 0;
}
