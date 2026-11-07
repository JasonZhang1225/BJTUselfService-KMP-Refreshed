# 桌面作业聚合与课件交互修正

基线：89fec5b（Duo 和横屏基本适配完成）。

- 聚合页在宽屏采用 40% 列表、60% 详情，保留列表与选中描边；点击三个来源的作业均在同一工作区加载详情，紧凑窗口继续使用独立详情路由。
- 三个来源共用详情框架、复制为 Markdown 和保存为 Markdown。课程平台复用已有正文／附件导出；物理在线包含已加载要求、反馈和提交文件；CITEL 导出已有任务元数据、提交状态／文件及原作业链接，不编造未加载的网页正文。
- 课件的本地课程与节点选择不再等待网络操作锁；缓存目录在后台刷新期间仍可选。晚完成的文件夹操作通过导航代次校验，不能覆盖后续选择。目录导出固定请求发起时的课程，不随 UI 切换改变来源。
- 桌面共享代码及应用入口编译通过。CoursewareScreenModelTest 17 项、AssignmentMarkdownTest 2 项、桌面分栏点击与渲染检查 1 项全部通过，共 20 项；日志 `.artifacts/desktop-assignment-courseware-tests.log`。
- 离线分栏图 `multiplatform/shared/build/reports/assignment-ui/desktop-split.png` 使用真实聚合列表、分栏容器和导出工具栏；详情正文为布局检查文本。未操作真实账号、提交或保存用户文件，未安装／启动替换用户当前运行的桌面包。

## Debug 首次启动同步状态

- 用户反馈首次启动同步慢，CITEL 一直显示等待同步。CITEL 当前策略是功能进入时按需同步，首页旧状态把未进入误写成等待；聚合页此前只恢复缓存，没有启动首次功能同步。
- 首页提示改为“按需同步 · 进入作业后更新”，有缓存时显示“显示缓存 · 进入作业后更新”；实际 busy／失败／完成状态不受提示覆盖。聚合页在功能进入时启用并发起 CITEL 会话内首次同步，使用 generation 0 去重，不监听窗口前台。
- 完整 Debug 启动会刷新多个模块；KtorSchoolHttpTransport 为共享 Cookie 稳定性串行化登录会话请求，sessionScoped 请求超时上限 30 秒，慢请求可阻塞后续模块。这是源码机制判断，当前 Debug 未启用请求耗时日志，不能据此认定本次哪个接口慢。
- HomeSyncStatusTest 与 CitelTest 以及桌面入口编译通过，日志 `.artifacts/desktop-sync-status-tests.log`。未主动重登、手动刷新真实账户或重启用户正在使用的 Debug 进程。

## 按用户要求重启 Debug 并记录耗时

- 添加独立 `--sync-timing` 参数，默认关闭。请求事件记录排队、开始、结束；模块事件记录墙钟耗时。只记录静态模块标签、主机、方法、HTTP 状态和毫秒数，不启用普通业务日志。
- 编译与 SyncTimingTest 通过，失败／取消的诊断事件不输出异常内容；日志 `.artifacts/desktop-sync-timing-build.log`。
- 已启动新的桌面 Debug，完成本轮启动同步；耗时摘要 `.artifacts/desktop-sync-timing-summary.md`，原始日志 `.artifacts/desktop-debug-timing.log`。课程平台作业约 49.4 秒、物理在线约 38.7 秒、课表约 33.5 秒、成绩约 20.8 秒；单次最长排队约 8.9 秒，单次最长请求约 8.0 秒。仅记录一次启动，没有额外触发刷新。
- 原安装版保持运行，未关闭或覆盖它；当前 Debug 继续运行并持续记录后续操作。

## 同步策略澄清与并行优化（替代此前 CITEL 按功能进入的策略）

用户明确：CITEL 每次进入 App 必须与其他模块并行同步一次；窗口聚焦不触发同步。之后仅在提交或实际读取确认会话过期时恢复，不通过网络慢推断登录失效。

- 首页稳定宿主调用 `refreshForAppEntry(0)`，模型同会话去重；独立 CITEL 页与聚合页也用相同代次，避免重复启动。已移除“进入作业后更新”的按需提示。
- 核对 [Ktor 3.5.1 AcceptAllCookiesStorage 官方源码](https://github.com/ktorio/ktor/blob/3.5.1/ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/cookies/AcceptAllCookiesStorage.kt)，get／addCookie 自带 Mutex，旧的“Cookie 不安全”注释不符合当前依赖。改为按主机独立 Semaphore：普通服务 2 条，CAS 1 条；公开页面及独立物理实验仍保留原 Cookie 归属与隔离。
- CITEL 课程与作业只读页两路并发，保留原任务顺序；登录恢复采用独立锁与代次，只在实际过期时登录，多个失效读取共用一次恢复。账号与作业写操作仍保留既有锁。
- 课程平台详情读取新增确认失效后的恢复闭包，NETWORK 不登录；既有提交失效恢复保留。
- 并发、Cookie、恢复与状态检查通过；日志 `.artifacts/parallel-sync-tests.log`、`.artifacts/citel-parallel-read-tests.log`。
- 最终一次真实启动记录：作业 6.9 秒、课表 5.2 秒、成绩 1.6 秒、CITEL 13.6 秒。网络响应也有变化，不将全部下降归因于并行。对比与限制见 `.artifacts/parallel-sync-comparison.md`；Debug 保持运行，原始记录 `.artifacts/desktop-debug-final-parallel-timing.log`。
