# 程控台

本机应用程序调度控制台。把桌面上的快捷方式收进这里，需要时再启动。界面按工业软件来排，名字和图标都能看出这是在集中启动程序：四格面板，不是游戏货架，也不是伪装成系统组件。

## 放到本机（不要用自定义 exe）

请下载 zip，用资源管理器解压后再运行。自编的安装 exe 在不少 Windows 11 上会被直接拒绝加载。

64 位系统（含常见 ARM 笔记本的兼容模式）用 `Chengkongtai.zip`。只有 32 位 Windows 才用 `Chengkongtai-x86.zip`。

1. 用**浏览器**下载 zip，不要用微信 / QQ 转发
2. 右键 → 解压到 `D:\Softwave\程控台`（若还没有这个文件夹，可先解压到任意位置）
3. 打开解压后的文件夹，双击 `Start.cmd`（或 `Start.vbs`，没有黑色窗口）
4. 若要固定到 `D:\Softwave\程控台`，再双击 `Install.cmd`

也可以双击 `Chengkongtai.exe`：它会写入 `D:\Softwave\程控台`（若该处已有隐藏的 desktop.ini，会先去掉系统属性再覆盖）。写不进 D 盘时会改放到本机 AppData。

文件夹里的 `程控台.exe` / `Chengkongtai.exe` 是官方 Python 的 `pythonw.exe` 副本，用来双击启动；真正干活的是旁边的 `pythonw.exe` + `main.py`。

若系统开着 S 模式，任何非商店程序都无法运行，需要先在「设置 → 系统 → 激活」退出 S 模式。

## 启动

- 解压后双击 `Start.cmd` 或 `Start.vbs`
- 源码目录也可双击 `启动程控台.vbs`（需要本机 Python 3.10+ 并加入 PATH）

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

重新打包 Windows zip：

```bash
python packaging/build_windows.py
```
