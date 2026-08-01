# AutoCrackApp Agent 能力路线图

## 1. 产品目标

AutoCrackApp 的最终目标不是一个“APK 解压器”，也不是只会查 DEX 字符串的聊天界面，而是一个运行在 Root Android 设备上的、可审计的移动端逆向分析工作台与 Agent。

用户选择一个已安装应用后，系统应能够围绕同一个分析会话持续完成：

1. APK / Split APK / OBB / 动态代码与私有目录盘点；
2. Manifest、资源、签名、DEX、SO 的静态分析；
3. Java / Kotlin 与 JNI / Native 代码之间的关联；
4. 目标进程启动、附加、模块加载与运行时行为观察；
5. 日志、文件、网络、Binder、数据库和加密调用的证据采集；
6. Frida、gdbserver / lldb-server、logcat、tcpdump 等工具的受控编排；
7. Agent 根据问题制定计划、调用工具、归并证据、给出带来源的结论；
8. 所有 Root、注入、附加、导出和修改动作均保留审计记录。

Root 权限提供能力，但不应等同于允许 Agent 执行任意 Shell。默认只读、工具参数强类型、目标包作用域限制、危险动作确认和完整日志是核心架构约束。

---

## 2. 最终界面结构

最终采用固定底部导航，而不是一个不断增长的长页面。

### 2.1 应用

- 已安装应用与系统应用搜索；
- 版本、UID、安装路径、进程名、ABI、数据目录；
- 最近分析会话；
- 新建、恢复、复制和删除分析会话；
- 选择 Base / Split、指定进程和主 ABI。

### 2.2 静态

- Manifest、权限、组件、Intent Filter、Provider；
- 资源、assets、证书、网络安全配置；
- DEX 类、方法、字段、字符串、调用关系和引用关系；
- SO / ELF、JNI、符号、依赖、字符串、反汇编；
- 混淆、加固、动态加载和风险线索；
- 本地证据搜索与模型分析。

### 2.3 动态

- 目标进程启动、附加和停止；
- 进程、线程、内存映射、已加载 DEX / SO；
- Frida Hook 与 Trace；
- logcat、文件访问、数据库、网络、加密 API 观测；
- gdbserver / lldb-server 调试会话；
- 运行时 DEX / SO dump 与证据入库；
- 动态事件时间线。

### 2.4 任务

- APK 提取、索引、SO 分析、动态采集等后台任务；
- 真实进度、开始时间、耗时、内存、输出大小；
- 暂停、取消、重试和恢复；
- 错误、异常、Root 命令结果与诊断报告；
- Agent 工具调用时间线和审计记录。

### 2.5 设置

- 外部模型、API Key 与联网边界；
- Frida Server、gdbserver、lldb-server、tcpdump 工具状态；
- 默认分析策略与资源限制；
- Root 操作授权级别；
- 数据保留、自动清理和导出设置。

当前 0.5.2 先把已有“应用 / 工作区 / 分析 / 模型 / 诊断”迁移到底部导航。动态能力进入实现后，再迁移到上述最终五区结构。

---

## 3. 分阶段实现

## Phase 5.2：现有链路工程化

目标：先让当前 DEX MVP 可持续使用，而不是每次分析都生成数百 MB 数据库。

### 必做

- 工作区复用：同一 APK SHA-256 命中已有索引；
- 分阶段真实进度：APK、DEX 条目、类、方法、字符串；
- 任务取消与退出后恢复；
- 可配置字符串索引策略；
- 默认优先索引应用自身包名，降低三方 SDK 噪声；
- 把依赖库、广告 SDK、统计 SDK 与应用自有代码分组；
- 搜索结果分页，不再一次只截取 80 条；
- 查询耗时统计和慢查询诊断；
- 会话存储大小统计与自动清理。

### 当前真机基准

- `com.kqkd.lsjm.bssp`：245,370,880 B 索引，55,087 ms 建立，26,451 ms 查询；
- `com.tencent.mm`：634,208,256 B 索引，163,148 ms 建立。

这说明全量 `%LIKE%` + 大规模字符串表只能作为 MVP，不适合作为最终检索架构。

---

## Phase 6：SO / ELF 静态分析

目标：从“只统计有多少个 SO”升级为真正的 Native 分析证据层。

### 6.1 ELF 基础解析

每个 SO 至少输出：

- ABI、ELF32 / ELF64、端序、e_machine；
- Program Header 与 Section Header；
- `.text`、`.rodata`、`.data`、`.bss` 等节区；
- 动态依赖 `DT_NEEDED`；
- SONAME、RPATH、RUNPATH；
- 导入符号、导出符号、动态符号；
- relocation 数量与类型；
- Build ID；
- 是否 stripped；
- PIE、NX、RELRO、BIND_NOW、Stack Canary 等加固信息。

### 6.2 JNI 关联

- 查找 `Java_<package>_<class>_<method>` 静态导出；
- 识别 `JNI_OnLoad`；
- 扫描 `RegisterNatives` 相关结构与字符串；
- 将 DEX 中的 `native` 方法与 SO 导出 / 注册项关联；
- 记录 Java 方法 → Native 函数 → 所属 SO 的证据链；
- 识别 `System.loadLibrary`、`System.load`、`dlopen`、`dlsym` 调用线索。

### 6.3 Native 字符串与 API 证据

- URL、域名、文件路径、证书、算法名、协议头；
- `ptrace`、`prctl`、`seccomp`、`fork`、`execve`；
- `open`、`read`、`write`、`mmap`、`mprotect`；
- OpenSSL / BoringSSL / mbedTLS；
- AES、RSA、SHA、HMAC、随机数；
- socket、connect、send、recv；
- anti-Frida、anti-debug、Root 检测字符串与导入函数。

### 6.4 反汇编

- 第一版使用 Capstone 对指定函数或地址范围反汇编；
- 不默认反汇编整个大型 SO；
- 优先反汇编导出函数、JNI 函数和命中证据附近的函数；
- 保存地址、机器码、指令、基本块和交叉引用；
- Agent 只能基于可引用的反汇编证据回答，不把猜测写成事实。

---

## Phase 7：动态观测基础设施

目标：在不修改目标代码的前提下，先建立稳定的运行时事实层。

### 7.1 进程与运行环境

- 列出目标 UID 的进程和服务；
- 启动主 Activity、指定 Activity 或仅附加现有进程；
- `/proc/<pid>/maps`、线程、FD、socket、mount、SELinux 上下文；
- 已加载 SO、匿名映射、memfd、动态 DEX 路径；
- 进程退出、崩溃和重启时间线。

### 7.2 日志与系统事件

- 按 UID / PID 过滤 logcat；
- tombstone、ANR、Java 崩溃和 Native 崩溃；
- Activity、Service、Broadcast、Provider 启动事件；
- 包安装、更新和进程状态变化。

### 7.3 文件与数据库观测

- 只读列出目标私有目录；
- 文件创建、修改、删除时间线；
- SharedPreferences、SQLite、Room 数据库结构；
- 指定文件快照与差异比较；
- 任何写入、替换或删除必须单独确认，默认不提供给 Agent 自动执行。

### 7.4 网络观测

- `/proc/net` 与连接目标；
- Root tcpdump 捕获；
- DNS、TCP、TLS 元数据；
- 可选 VPNService 抓包模式；
- HTTP 明文与证书链分析；
- TLS 解密仅作为明确启用的受控工具，不默认绕过证书校验。

---

## Phase 8：Frida 工具层

目标：把 Frida 变成强类型、可审计的 Agent 工具，而不是任意 JavaScript 输入框。

### 8.1 工具管理

- 检测设备 ABI 与 Android 版本；
- 安装、启动、停止指定版本的 frida-server；
- 验证端口、进程和版本兼容性；
- 不把未知来源二进制静默放入系统目录。

### 8.2 标准 Hook 模板

- Java 方法参数、返回值和异常；
- `System.loadLibrary` / `dlopen`；
- `RegisterNatives` 与 JNI 映射；
- OkHttp、URLConnection、WebView；
- SharedPreferences、SQLite；
- Cipher、MessageDigest、Mac、Signature；
- 文件 API；
- socket / SSL API；
- 指定 Native 导出和地址偏移。

### 8.3 事件模型

每条 Hook 事件必须记录：

- 时间；
- 目标 PID / TID；
- Hook 模板与版本；
- 类 / 方法 / 模块 / 地址；
- 参数与返回值的受限预览；
- 调用栈；
- 原始事件文件位置；
- 是否发生截断或脱敏。

### 8.4 权限边界

- 默认只观察，不修改返回值；
- 修改参数、返回值、内存或控制流必须明确确认；
- Agent 不接受任意 Frida JavaScript 作为默认工具调用；
- 所有脚本具有 ID、版本、参数 Schema 和审计记录。

---

## Phase 9：Native 动态调试

目标：建立面向 SO 的可重复调试工作流。

### 9.1 gdbserver / lldb-server

- 选择进程或 spawn；
- 附加状态、线程、寄存器、内存映射；
- 模块基址与 ASLR 重定位；
- 符号文件和本地 SO 对应；
- 断点、命中次数和调用栈；
- 指定地址附近内存与反汇编；
- 崩溃现场保存。

### 9.2 动态 Native 证据

- `dlopen` / `android_dlopen_ext`；
- `dlsym`；
- `mmap` / `mprotect`；
- JNI 注册；
- 加解密函数参数；
- 网络发送前后的缓冲区；
- 动态解密后的代码或数据区域识别；
- 指定模块的只读 dump 与静态分析回灌。

### 9.3 控制原则

调试器可以暂停目标进程，因此必须：

- 明确展示当前附加状态；
- 提供一键 detach 与恢复；
- 设置超时；
- 避免 Agent 无限暂停进程；
- 将断点和内存读取限制在当前目标会话。

---

## Phase 10：真正的 Agent 编排

当前“一句话分析”只是关键词扩展和本地检索。真正 Agent 需要完整的工具循环。

### 10.1 核心循环

1. 理解用户目标；
2. 读取已有会话与证据；
3. 生成可审计计划；
4. 选择强类型工具；
5. 对高风险步骤请求确认；
6. 执行并收集结构化结果；
7. 判断证据是否足够；
8. 必要时继续下一步；
9. 输出“确认事实 / 推断 / 未知 / 下一步”。

### 10.2 工具类型

- `inspect_manifest`
- `search_dex`
- `find_callers`
- `inspect_elf`
- `search_native_symbols`
- `disassemble_function`
- `list_processes`
- `read_process_maps`
- `start_log_capture`
- `attach_frida_template`
- `start_network_capture`
- `attach_debugger`
- `read_memory_range`
- `dump_loaded_module`

每个工具都需要：

- JSON 参数 Schema；
- 目标包 / PID 作用域；
- 风险级别；
- 超时；
- 最大输出；
- 是否需要用户确认；
- 可重复执行标识；
- 审计事件。

### 10.3 Agent 不能直接获得的能力

- 任意 Root Shell；
- 任意文件写入；
- 任意进程注入；
- 任意内存修改；
- 静默关闭安全机制；
- 静默导出凭据或个人数据。

这些限制不是削弱功能，而是避免一个解析错误或模型幻觉直接破坏设备、目标应用或用户数据。

---

## 4. 数据架构

建议把当前临时工作区升级为统一会话数据库。

### AnalysisSession

- sessionId
- packageName
- versionCode / versionName
- APK SHA-256 集合
- device fingerprint
- createdAt / updatedAt
- selected ABI / process
- state

### Artifact

- APK、DEX、SO、资源、证书、数据库、日志、抓包、内存 dump；
- 文件哈希、来源、时间、大小、父 Artifact；
- 是否来自静态提取或运行时 dump。

### Evidence

- 类型；
- 来源 Artifact；
- 地址 / 类 / 方法 / 文件偏移；
- 摘要；
- 原始内容位置；
- 置信度；
- 静态或动态；
- 关联 Evidence。

### ToolRun

- 工具 ID 和版本；
- 输入参数；
- Root 命令的结构化表达；
- 开始 / 完成时间；
- 退出状态；
- stdout / stderr 的受限存档；
- 输出 Artifact / Evidence；
- 用户确认记录。

### Finding

- 标题；
- 结论；
- 已确认事实；
- 推断；
- 未知；
- 证据引用；
- 复现步骤；
- 风险与影响。

---

## 5. 推荐的下一步开发顺序

不要立即同时接入 Frida、GDB、抓包和完整反汇编。合理顺序是：

1. 完成底部导航和任务模型；
2. 修复 DEX 索引性能、缓存和三方库噪声；
3. 实现完整 ELF / SO 静态分析；
4. 实现 DEX native 方法与 JNI 映射；
5. 实现进程、maps、模块、logcat 的只读动态观测；
6. 接入 Frida Server 管理和标准 Hook 模板；
7. 接入网络与文件行为采集；
8. 接入 gdbserver / lldb-server；
9. 最后实现 Agent 多工具自主规划。

下一阶段应定义为 **Phase 6：Native Static Analysis**，首个可验收目标不是“SO 数量”，而是对每个 SO 输出可搜索的 ELF、依赖、符号、加固、JNI 和字符串证据，并让一句话分析能够同时检索 DEX 与 Native 证据。
