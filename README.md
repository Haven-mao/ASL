# ASL — Android Subsystem for Linux

ASL 在 Android 平板上运行独立的 Debian ARM64 Linux 内核，默认 2 vCPU / 4 GiB，提供 systemd、Docker、SSH、多终端和 Codex。当前 0.5 已在小米 Pad 7 Pro（Android 16 / HyperOS 3）安装验证。

ASLTree 负责可复用的应用操作记忆，独立维护于 [Haven-mao/ASLTree](https://github.com/Haven-mao/ASLTree)。ASL 在 `external/ASLTree` 固定引用其提交；该仓库当前为私有仓库，需要相应访问权限。设备数据、密钥、虚拟磁盘和 API 配置均不进入本仓库。

## 0.5 的应用控制

- 前台控制：读取平板当前应用的界面树、截图，点击、输入、滚动和返回。
- 后台控制：独立 Android 虚拟屏幕、独立焦点，主屏可继续使用。ASL 菜单可实时预览后台画面。
- 任务完成：Codex 发出完成通知；用户点“查看结果”打开保留原页面的后台预览，也可选择“打开原应用”把匹配的应用任务移到主屏。
- ASLTree：学习一次成功操作，把输入作为参数保存；下次重新定位控件并逐步检查结果。菜单“ASLTree 操作记忆”展示已学习流程和运行记录。

每次点击都使用当前节点或截图引用。节点和截图引用会过期，应用升级会使旧流程失效；失败流程需要检查后才能继续复用。自绘或复杂多窗口页面仍可能需要模型重新查看截图，不保证全应用自动遍历。

## 在平板上使用

安装 APK 后首次导入 ASL 系统镜像。已经使用 ASL 的设备直接升级 APK，保留现有磁盘；不要卸载或重新导入通用镜像覆盖数据。

1. 启动 Linux，在终端运行 `codex`。账号登录使用你自己的配置。
2. `⋮ → Android 控制授权` 开启无障碍服务。HyperOS 的应用省电策略设置为“无限制”。升级后如果系统撤销服务，需要重新开启。
3. 左上角 `+` 新增独立终端，标签 `×` 关闭。有前台任务时会确认；每个标签有独立画面和历史。触摸滚动查看历史，点“回到最新”恢复跟随。
4. 实体键盘 Esc 发送给终端，Ctrl 组合键正常使用，Ctrl+Shift+C/V 复制粘贴。
5. 使用应用“关机”或 `sudo poweroff` 正常关闭 Linux。

## 后台屏幕

在已经授权 USB 调试的电脑上激活辅助组件：

```powershell
$env:ASL_ADB = 'adb.exe 的路径'
node scripts/activate_background.mjs
```

然后选择 `⋮ → Android 控制屏幕 → 后台虚拟屏幕`。通过 `⋮ → 查看 Codex 后台画面` 查看进展。平板重启或辅助组件被系统结束后需重新激活；不需要 root。

后台结果默认在预览中查看。实测抖音在跨屏迁移后会重建搜索页，因此“打开原应用”不能保证所有应用都保留原页面；不会自动重做可能有副作用的操作。

后台组件使用已授权的 ADB shell 权限创建显示屏，设置独立焦点和禁止抢主屏焦点，并只保持该显示屏唤醒。平板锁屏时停止操作。它使用原有应用和账户数据；同一应用的单实例、跨屏重建和安全画面限制仍由 Android 与应用决定。主屏正在显示同一个应用时，后台启动会拒绝，避免迁移正在使用的页面。

## 学习与复用

可以对 Codex 说：

> 学习这次抖音搜索流程，把关键词作为参数。确认结果页出现后保存，下次优先复用。

现有 `android` MCP 已包含 `asltree_*` 工具，无需复制或修改 Codex 的 API 凭据。操作记忆保存在 Linux 用户的 `~/.local/share/asltree`。后台完成后使用 `android_finish_task` 通知用户；切回结果由用户触发。

已验证的示例包括计算器参数化加法、抖音搜索换词复用。验证方法、时间和边界见 [VALIDATION.md](VALIDATION.md)。

## 构建

```bash
git clone --recurse-submodules https://github.com/Haven-mao/ASL.git
cd ASL
python3 scripts/sync_asltree.py
```

APK：Linux/WSL、Java 17、Android SDK platform 36 / build-tools 36.0.0。Android/Bionic QEMU 及其依赖通过官方 Termux 包仓库准备并校验 SHA256。

```bash
python3 scripts/prepare_android_runtime.py --index Packages.gz --cache /path/to/termux-cache --out build/runtime-bionic
ASL_ANDROID_SDK=/path/to/sdk bash scripts/build_apk.sh
ASL_ANDROID_SDK=/path/to/sdk bash scripts/build_display_worker.sh
```

使用 Android/Bionic 运行时；`prepare_runtime.py` 是早期 musl 原型，不用于当前设备。请保留本地签名密钥以便后续覆盖升级；签名密钥不提交到 Git。

通用 guest 镜像：root、debootstrap、qemu-user-static/binfmt、qemu-utils、e2fsprogs、curl、gpg，以及 SHA256 固定的 Codex 0.160.0 ARM64 安装包。

```bash
sudo ASL_CODEX_ARCHIVE=/path/to/codex-package-arm64.tar.gz bash scripts/build_guest.sh
```

默认镜像账户为 `asl`，不包含开发 SSH 私钥或用户凭据。`scripts/update_guest.mjs SSH_KEY USER [PORT]` 更新已有 guest 组件；正常更新会重启 guest agent 并断开 PTY，应先保存任务。APK 升级时可设置 `ASL_DEFER_RESTART=1`，写入文件后统一正常关机，让下次启动加载更新。

## 测试

```bash
python3 -m unittest discover -s tests -p test_android_mcp.py -v
cd external/ASLTree && python3 -m unittest discover -s tests -v
```

`tests/test_pty.py` 在真实 Linux guest 内验证独立终端、Unicode、尺寸变化、关闭保护和持续输出。它不操作已有终端。

当前使用 TCG 软件虚拟化；设备未提供可用的 KVM/AVF 加速。Codex 冷启动仍可能较慢。Docker 容器已运行验证，guest 直接连接 Docker Hub 曾超时。第三方组件出处见 [THIRD_PARTY.md](THIRD_PARTY.md)。
