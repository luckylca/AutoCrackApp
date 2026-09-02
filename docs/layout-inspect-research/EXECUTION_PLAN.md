# Layout Inspect → AutoCrackApp 完整迁移执行计划

## 目标

以 `/Users/lucky/Desktop/project/AutoCrackApp` 为唯一主工程，完整研究 `/Users/lucky/Desktop/project/LayoutInspectResearch` 中 Layout Inspect 的真实实现，并将主要能力按 AutoCrackApp 现有 Toolpack + rootfs + Agent 架构重新实现。

最终约束：Toolpack 可选、目标进程 Runtime 共享、对象 Handle 可跨工具传递、SimpleHook 独立、Agent 继续主要通过 Bash CLI 组合能力。

## 当前基线

- 初始分支：`codex/frida-capabilities-1.0.4`
- 本轮接手 HEAD：`e46c3e2e678edd380c62c3c23f21faea6ddf4238`
- 已存在研究提交：`research: map Layout Inspect implementation`
- 接手时工作区存在未提交的共享 Runtime / Toolpack 迁移改动，必须先审计并保留，不覆盖、不回滚用户已有工作。

## Phase A — Layout Inspect reverse engineering

1. 完整利用 APK / jadx / apktool / smali / dex / so / resources / notes。
2. 对任务书全部能力建立 implementation map：UI 功能、类/方法/字段、调用链、Hook 点、hidden API、Xposed/root/proc/ART/linker 依赖、数据回传、版本差异、fallback。
3. 形成并持续修订：
   - `FEATURE_MATRIX.md`
   - `IMPLEMENTATION_MAP.md`
   - `AUTOCRACK_MAPPING.md`
   - `ANDROID_VERSION_MATRIX.md`
   - `RUNTIME_ARCHITECTURE.md`
   - `TOOLPACK_BOUNDARIES.md`
   - `TEST_MATRIX.md`
4. 对无法确认的功能明确标注证据等级，不用猜测冒充逆向结论。

## Phase B — Shared AutoCrack Runtime foundation

1. 将 `simplehook-runtime` 与 `runtime-inspector-runtime` 的重复目标进程基础设施收敛为一套 `AutoCrack Runtime`。
2. 一个 Companion APK、一个 Xposed Module、一套 bootstrap / dispatcher / request-result channel。
3. 建立共享 Registry：
   - `ClassLoaderRegistry`
   - `ObjectRegistry`
   - `WindowRegistry`
   - `ActivityRegistry`
   - optional `ViewCreationTracker`
4. 建立稳定 capability namespace：`ui.*`、`runtime.*`、`object.*`、`classloader.*`、`memory.*`、`webview.*`、`hook.*`、`control.*`。
5. Observer/Hook 去重：ClassLoader、WindowManager、View creation、Activity observer 每套只安装一次。

## Phase C — Shared rootfs Runtime Client + Toolpack manifest v2

1. 抽取 `android-shell` / provider / content call / base64 / polling / timeout / error parsing 为公共 Runtime Client。
2. 评估并完成 Android Host Bridge 基础化，同时兼容旧工具。
3. 实现 Toolpack Manifest schema v2：runtime version、required/optional capabilities、commands，同时保持 schema v1 兼容。

## Phase D — SimpleHook migration

1. SimpleHook CLI 与规则语义保持独立。
2. 仅迁移底层目标进程 Runtime/channel/registry 到共享 Runtime。
3. 回归现有 SimpleHook 完整真机测试矩阵，确保迁移不破坏既有 21/21 类能力。

## Phase E — ui-inspect

实现并验证：windows、tree、pick/at、parent/children、props、listeners、creation/inflate/add stack、View→image、visibility/remove/modify、TextView/ImageView/AdapterView/VideoView/WebView 基础能力、SystemUI target、Compose capability 状态。

重点：多 root Window、正确 hit-test/drawing order、Main Looper mutation、draw(Canvas) + PixelCopy fallback、listener→SimpleHook 闭环。

## Phase F — runtime-inspect

实现并验证：process info、running/declared activities、Activity instance、ClassLoader registry、class search/describe、methods/fields/constructors/interfaces/inners、Object handle/preview/dump/release。

ObjectRegistry 默认 WeakReference，支持 pin/TTL/session/max count/release/stale/package-process-pid binding。

## Phase G — memory-dump

实现并验证：maps、range、module/SO、Dex、XML、Assets。

策略必须区分 root `/proc` 与 target Runtime；Dex/XML/Assets 不能退化成简单 APK 文件复制后仍宣称 runtime dump。Android/ART/linker 特异能力以 capability/strategy 返回真实支持状态。

## Phase H — runtime-control

实现并验证：Activity start、process kill、SO inject、FLAG_SECURE status/disable、WebView list/debug/eval，以及其他 runtime mutation。

所有 UI mutation 在 Main Looper 执行；危险/版本特异操作提供明确策略和错误返回。

## Phase I — Cross-tool integration

必须打通至少四条闭环：

1. `ui-inspect at` → Object Handle → `runtime-inspect object`
2. View → listener → class describe → SimpleHook rule → click → hook log
3. ClassLoader Handle → `memory-dump dex --loader` → dex → jadx
4. WebView discovery → debug enable → JS eval

这一步作为共享 Runtime 是否真正成立的核心验收。

## Phase J — Validation / cleanup / delivery

1. Host tests、Gradle build、Toolpack self-test。
2. Android 真机矩阵：Dialog、PopupWindow、多 Window、listeners、mutation、image、WebView、ClassLoader/DexClassLoader、Object handle、Activity、maps、SO/Dex dump、WebView debug、FLAG_SECURE、SimpleHook 回归。
3. 所有 CLI `--json` 输出稳定；统一 `{ok:false,error:{code,message}}` 错误结构。
4. 验证 max nodes/classes/fields/methods/dump bytes/object depth/handles/timeouts/rate limits。
5. 清理临时构建文件，检查 diff/status，再按完整阶段提交 Git。
6. 最终报告明确“完全实现 / 部分实现 / 未实现”，不给模糊结论。

## Git 纪律

- 每个完整工程阶段一个或少量语义明确 commit。
- commit 前检查 diff、测试结果、临时文件。
- 不重写已有成熟基础设施，不强行提交不稳定代码。
- 不覆盖接手时已有未提交工作；先识别来源和完整性后再纳入对应阶段。

## 执行顺序

当前从 **Phase A 研究审计 + 当前未提交共享 Runtime 改动审计** 开始；确认研究证据和现有实现后，继续 Phase B→J，不因单项失败停止整个任务。
