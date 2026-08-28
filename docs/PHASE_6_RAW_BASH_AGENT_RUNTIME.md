# Phase 6 — Mobile Pi Agent

目标：把 AutoCrackApp 做成直接运行在 Android 手机上的通用 Pi-style Agent，而不是 APK 分析向导。

## 用户界面
- 仅保留“会话 / 设置”两个主 Tab。
- 会话页是持久化会话列表；左上角 `+` 新建会话。
- 新会话直接输入自然语言目标，不要求包名、APK 选择或预先建立工作区。
- 设置包含 API、权限检查、工具包管理。
- 不提供用户侧“调试模式”。

## Agent Runtime
模型只有四个原语：`exec_bash`、`read_file`、`write_file`、`kill_process`。

专业能力来自 toolpack。每次创建 Agent runtime 时枚举已安装 toolpack 的 commands，并把命令列表放入模型上下文。安装新 toolpack 后，无需新增 Kotlin wrapper，后续会话即可通过 Bash 使用新命令。

## 最小边界
- 每会话独立 workspace
- timeout
- 输出上限
- audit log
- kill switch

## 当前进度
- [x] Raw Bash 四原语
- [x] Generic Mobile Agent session，不绑定 APK/package
- [x] per-conversation workspace
- [x] toolpack command discovery
- [x] persistent conversation store
- [x] Pi-style conversation UI
- [x] API / 权限检查 / toolpack 设置页
- [x] 真机安装验证

## Mobile Pi Agent 八项产品任务

下面八项是当前阶段必须完成的产品能力。设计参考 Claude Code / Pi 一类 Agent：保持模型动作面简单，同时把会话、上下文、任务生命周期和工具发现做好。

1. [x] **真实多轮消息协议**：持久化并重放 `user / assistant / tool` 消息以及 assistant 的 tool calls，不再把历史拼成一整段 user prompt。
2. [x] **停止当前 Agent**：用户可立即停止模型请求、tool loop 和当前 rootfs/host 命令，并清理本轮资源。
3. [x] **会话附件**：聊天输入可直接附加 APK、图片、压缩包或任意文件；文件复制到当前 session workspace，由 Agent 通过原生命令自行处理。
4. [x] **流式输出与实时状态**：模型文本增量显示，工具执行阶段可见但默认保持简洁。
5. [x] **长对话与上下文压缩**：保留近期原始消息，把更早历史压缩为持久化 summary；完整历史仍保存在本机，不因压缩丢失。
6. [x] **Toolpack 能力描述**：manifest 可为工具包和每条 CLI 命令提供可选 description，Agent prompt 动态展示；模型需要细节时仍直接调用 `--help`。
7. [x] **后台任务与恢复**：Agent 执行脱离 Compose 页面 coroutine，由进程级 coordinator + foreground service 持有；Activity 重建后可重新看到运行/完成状态，异常进程重启时保留中断记录。
8. [x] **会话管理**：搜索、重命名、删除；删除会话同时清理对应 workspace。

### 验证
- `scripts/check_android_rootfs_only.sh`
- `testDebugUnitTest`
- `assembleDebug`
- 真机安装/启动与关键 UI 可见性检查
