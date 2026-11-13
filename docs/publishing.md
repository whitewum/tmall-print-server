# 公开发布准备

## 推荐发布物

先发布源码。`python3 tools/export_source.py` 从 Git 跟踪文件生成无 Git 历史的源码 ZIP，
并拒绝常见密钥、固件、APK 路径及个人主目录路径。
设备标识、文档中的网络信息和其他敏感内容仍需人工检查。
生成位置为 `build/tmall-print-server-source.zip`，旁边附 SHA-256 文件。
这不是完整安全审计，发布前仍需审阅源码和许可。

原私人仓库的旧提交保留了开发历史，可能包含家庭 IP、主机名及设备信息。
源码 ZIP 不带这些历史。如需一个干净的公共 Git 仓库，可将 ZIP 解压到新目录后初始化：

```sh
git init
git add .
git commit -m "Initial public source release"
```

提交作者名、邮箱和 GitHub 目标仓库由发布者自行选择。本工具不会创建远程仓库、推送或上传。
不要直接上传整个工作目录、NAS 共享目录或私人恢复备份。

## 已排除的内容

- `.local/` 中的签名密钥和本机状态。
- HP 固件、原始驱动、生成的编码器及 APK。
- SDK/NDK、编译产物、测试输出、录音、完整系统日志和 NAS 恢复备份。
- Git 历史，以及电脑/NAS 的账户和共享资料。

## 发布 APK 前

当前本机 APK 内包含用户自己准备的 HP 固件，不能直接当作公开下载包。
若未来要分发 APK，应先设计用户自行导入固件的流程，或核实固件的再分发条件；
本轮没有实现固件导入功能。
同时保留编码器 GPL 对应源码、版本、构建命令、许可证和改动说明。
维护者的正式签名私钥应独立管理，不能公开，也不要用本地测试密钥充当正式发布密钥。

## 验证

```sh
python3 tests/selection/run.py
python3 tests/http/run_tests.py
python3 tools/build_native.py
python3 tools/prepare_firmware.py /path/to/HP1005.acl
python3 tools/build.py
python3 tools/export_source.py
```

前两项不打印纸张。构建与导出不会安装 APK、修改设备或发布到外部网站。
已验证的设备和仍待验证的可靠性项目见 verification.md。

## 本轮准备结果（2026-11-13）

- 页码解析 29/29、HTTP 模拟检查 63/63 通过，没有产生纸张输出。
- 上游源码 SHA-256 与来源记录一致，原始上游许可文件未修改。
- 本机 ARM 编码器和签名 APK 构建通过；APK 仍仅供本机私人使用。
- 公开源码已移除家庭 IP、启动 ID 等调试记录，示例 IP 标明为示例。
- 已补充 GitHub Actions 的 Java/HTTP 检查配置；尚未在 GitHub 执行。
- 尚未创建远程仓库、上传源码或发布 APK。
