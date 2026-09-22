# 程控台

本机应用程序调度控制台。把桌面上的快捷方式收进这里，需要时再启动。界面按工业软件来排，名字和图标都能看出这是在集中启动程序：四格面板，不是游戏货架，也不是伪装成系统组件。

## 放到 D:\Softwave

需要 **64 位 Windows**。用浏览器下载 `程控台.exe`（或 `Chengkongtai.exe`），双击即可：

- 若存在 `D:\Softwave\新建文件夹` 且还没有 `程控台`，先把新建文件夹改名
- 把程序解压到 `D:\Softwave\程控台`
- 写入文件夹图标并打开程控台

不要用微信 / QQ 转发这个 exe，聊天软件常会把安装包传坏，Windows 就会提示「无法在电脑上运行」。请从 GitHub 用浏览器下载。

安装包自带 64 位 Python 运行库，本机不用再装 Python。日常启动双击 `D:\Softwave\程控台\程控台.exe`。

源码目录里也可以执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\放置到本机.ps1
```

那会把当前文件夹复制到 `D:\Softwave\程控台`，仍可用系统 Python 跑 `启动程控台.vbs`。

## 启动

- 安装后双击 `程控台.exe`：没有黑色命令行窗口，也不依赖系统 Python
- 源码方式需要 Python 3.10 或更高版本，安装时勾选 **Add python.exe to PATH**，然后双击 `启动程控台.vbs`

程序只监听 `127.0.0.1`，台账写在本文件夹的 `data\library.json`。把整个文件夹拷走，登记过的程序会一起走。

## 能做什么

- 登记、编辑、移除程序，可填启动参数、工作目录和备注
- 扫描桌面 `.lnk` 并勾选导入；确认后可以把这些快捷方式移入回收站，不删除程序本身
- 按办公、研发、工具、媒体、其他分类，另有常用和最近
- 检索、排序，卡片或列表
- 记录启动次数和最近运行
- 打开所在位置、复制路径
- 导出台账、合并导入备份

影音和互动程序放在「媒体」。卡片上仍显示程序自己的名字。

## 开发检查

在本目录执行：

```bash
python -m unittest discover -s tests -v
```

重新打包 64 位 Windows 安装程序（Linux 上需要 `mingw-w64`）：

```bash
python packaging/build_windows.py
```
