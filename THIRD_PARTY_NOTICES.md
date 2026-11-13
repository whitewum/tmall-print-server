# 第三方源码和本地固件

## OpenPrinting foo2zjs / foo2xqx / JBIG

`native/upstream/` 中保留了所需编码器源码及原始版权声明，按 GPL 第 2 版或更高版本提供。
原作者包括 Robert Szalai、Rick Richardson、Markus Kuhn；各文件的原始声明具有优先性。
来源、提交版本与 SHA-256 校验值见 [native/UPSTREAM.md](native/UPSTREAM.md)。
上游 `COPYING` 包含它所分发的其他资产说明，不代表本仓库包含了那些资产。
本仓库只保留 UPSTREAM.md 列出的源码与 COPYING；未修改上游源码。
分发编码器二进制或含它的 APK 时，需要遵守相应 GPL 条款并提供对应源码和构建资料。

## HP 打印机固件

P1007 需要在打印机 RAM 中加载固件。本项目会从用户本机合法取得的 HP 驱动文件准备它；
准备工具的默认输入是 macOS 已安装 HP 驱动中的 HP1005.acl。
准备和加载不会刷写盒子系统，也不是给打印机永久升级固件。

`assets/sihpP1005.dl`、原始 ACL/IMG、厂商驱动及包含该固件的本机 APK 均不包含在公开源码包里。
这些文件不因本项目的 GPL 许可证而获得新的分发许可。
在没有核实厂商分发条件前，不要把本机已生成的 APK 上传到公开 Release。
项目不提供第三方网盘驱动或固件下载链接。

## Android 构建依赖

JDK、Android SDK 和 Android NDK 由开发者自行从相应官方来源取得，适用各自许可证，
不包含在公开源码包中。源码构建所需版本和命令见 BUILD.md。
