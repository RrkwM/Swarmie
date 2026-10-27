# Swarmie 项目进度

> 本文件只记录状态与索引，**不复制规范正文**。
> 规范：[`SPEC.md`](SPEC.md) ｜ 环境设计：[`docs/environment-plan.md`](docs/environment-plan.md) ｜ 版本基线：[`docs/version-baseline.md`](docs/version-baseline.md) ｜ 归档：[`docs/archive/`](docs/archive/README.md)

- 项目目标：Minecraft 26.3 + Fabric 的大规模并行可编程悬浮机器人
- 当前阶段：**0.1.0 首个公开版本已准备发布：MIT、SemVer 规范、CHANGELOG、CI 与 GitHub Release**
- 运行验证：**真实客户端 + Python 完成 mine/fly/stop/duplicate/cancel/reconnect、维度 active 中断、石头放置和库存消耗**
- 代码状态：**`contract` + `adapter.registry/event/io/transport`；版本 `0.1.0`；JAR 162,555 bytes，SHA-256 `fe3007e7…`**
- 环境状态：**原工作区为只读备份；runtime 为唯一活动工作区；未修改 ACL/系统设置**
- 最后更新：2026-10-28

## 1. 文档角色

| 路径 | 角色 | 来源 |
|---|---|---|
| `SPEC.md` | 完整开发规范（六关卡、架构约束、验收标准） | 原 `PROGRESS.md` 的逐字节副本 |
| `docs/phase-0-brief.md` | Phase 0 审计任务书（历史原文） | 原 `SPEC.md` 的逐字节副本 |
| `PROGRESS.md` | 本文件：状态跟踪 | 新写 |
| `docs/archive/` | 两个原始文件的只读归档 | 见该目录 `README.md` / `SHA256SUMS.txt` |
| `docs/adapter-contract.md` | Adapter Contract 0.1 字段、版本、序列化、限制、错误码 | 新写 |
| `docs/adapter-registry.md` | 注册表、生命周期、serverEpoch、事件环与实测 | 新写 |
| `docs/adapter-io.md` | 入站/出站、幂等、批量、事件总线 | 新写 |
| `docs/adapter-command-specs.md` | 四命令完整规格、副作用、取消、容器限制 | 新写 |
| `docs/local-transport.md` | 默认关闭的 localhost JSONL 传输与 Python 客户端 | 新写 |
| `docs/test-matrix.md` | 最终测试矩阵与证据分类 | 新写 |
| `docs/testing.md` | JUnit、证据分层、真实客户端测试说明 | 新写 |

## 2. 六个关卡状态

| # | 关卡 | 状态 | 说明 |
|---|---|---|---|
| 1 | 构建环境与最小 Mod | 构建/客户端通过（干净副本）；原工作区仍阻断 | 在普通用户副本中 Loom 1.19.3 构建成功，79,806-byte JAR；客户端到达主菜单并进入单人世界；原工作区 ACL 仍不能直接 build |
| 2 | 真实 Entity 与完整生命周期 | 运行时部分验证 | summon/渲染/保存重载、active removal 清理、chunk unload/reload 通过；第二客户端未验证 |
| 3 | 三维悬浮运动 | 运行时部分验证 | X/Y/Z timed flight、stop、单面墙碰撞通过；高速/斜向/台阶/天花板未验证 |
| 4 | Runtime 与命令执行 | 运行时部分验证 | FIFO/双队列、active/queued/cancelall/恢复、正常失败隔离通过；INTERRUPTED 内部历史/queued-only removal/编程异常未验证 |
| 5 | 原生交互与 InteractionActor | 运行时部分验证 | 持久化、挖掘/耐久、放置计数、失败库存完整性、打火石/lever 通过；chest 返回 SUCCESS 但 FakePlayer.openMenu 被置空、内容不变（无连接）；已调查 `MenuProvider.createMenu + AbstractContainerMenu.clicked` 可行但未实现/未 live 验证；保护/复杂方块未验证 |
| 6 | 世界查询与局部传感器 | 运行时部分验证 | block/region、UNLOADED、incomplete region、方块/实体 FOV、超距、32-result 截断通过；live entity query/incomplete sensor/LOS 未验证 |

## 3. 候选版本（未锁定，待构建实测）

| 组件 | 候选值 |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.165.0+26.3 |
| Loom | 1.19.3 |
| Gradle | 9.8.2（Loom 1.19.3 要求） |
| JDK | Temurin 25.0.4.1（项目内便携副本） |
| 映射 | 无（MC 26.3 非混淆；Loom 拒绝 `officialMojangMappings()`） |

依据与待验证项见 [`docs/version-baseline.md`](docs/version-baseline.md)。

## 4. 关键决定

- **D-001**：文档权威冲突已解决——`SPEC.md` 为规范，`docs/phase-0-brief.md` 为任务书历史副本，`PROGRESS.md` 为状态跟踪；原始文件全部归档。
- **D-002**：Self-Contained 目标冻结为“一次授权 provisioning 后，仅依赖工作区/离线包离线构建”，禁止用户绝对路径、全局 Gradle 缓存、系统 Gradle、可变全局 JDK、构建期联网、未记录的外部工具。
- **D-003**：优先 **C2**（常规构建只解析项目内 `file:` 仓库），C1 仅作为明确的降级方案，不得静默替代。
- **D-004**：JDK、Gradle 发行包、依赖仓库、Loom/MC 缓存全部放在项目内，由脚本设置进程级环境变量，不改 PATH/注册表/全局配置。

## 5. 已知风险 / 未决问题

- 已有可运行的开发客户端（`runClient`）；未安装到用户正式 launcher profile，也未测试正式发行环境。
- 根因已定位：原始工作区带 DSH Low/受限 ACL，Java `Files.isWritable()` 对实际可写文件返回 false，ZipFS 只读。普通用户副本中 ContextProbe 通过；原始工作区直接 build 仍失败。详见 `docs/runtime-verification.md`。
- 2026-10-27 Runtime Verification Sprint：普通用户副本中构建成功（79,806-byte JAR，SHA-256 `dd529fd8…`）；客户端到达主菜单、进入单人世界；完成 summon/render、X/Y/Z、stop、碰撞、Runtime status/results、库存、挖掘/耐久、放置、block/region query、sensor FOV 实测。专用服务器未测试（EULA 未接受）。
- 2026-10-27 Regression Sprint：保存/重载 UUID/位置/三种库存通过；FIFO active/queued barrier 与双机器人独立队列通过；active/queued/cancelall/恢复通过；不可破坏/空气/未加载交互拒绝且库存不变，打火石火焰通过；传感器实体前后、超距、32-result 截断通过。源码未改，JAR hash 不变；剩余项见 docs/runtime-verification.md §9。
- 2026-10-27 Edge-case Sprint：active Runtime removal 后无残留执行/新 Runtime 为空；chunk unload 时实体不可查询，重载后 UUID/位置/库存一致且仅一个实例；C 失败 #3 后 #4 成功、独立 E 成功；chest 返回 SUCCESS 但 FakePlayer.openMenu 返回 empty、内容未变（无连接），lever 正确切换；loaded/unloaded 混合区域 complete=false、unavailable=201，查询未隐式加载。源码/JAR 未变。
- Loom 的 Minecraft 资产下载是否完全服从 `--offline` 未验证（C2 的关键约束）。
- Loom 注入项目仓库与 `PREFER_PROJECT` 的 C2 合规性未验证。
- 26.3 `EntityRendererRegistry` 已弃用（替代为 `EntityRenderers.register` + Fabric transitive AW）；当前使用弃用 API，但本次客户端已实际渲染成功。
- Gate 3 的碰撞轴处理已通过单面墙测试；slab/stairs/edge、高速和斜向碰撞未验证。
- Gate 4 的 Runtime 已通过 FIFO/取消/失败隔离；编程异常、实体移除 INTERRUPTED 内部历史、quantitative 减速曲线仍未验证。
- Gate 5 的库存/挖掘/放置/失败库存完整性已通过；chest 的 MenuProvider GUI 因 FakePlayer.openMenu no-op 不可用；服务端 menu-click 适配器仅调查、未实现/未 live 验证；保护区域/第三方复杂 GUI 未验证。
- Gate 6 的 block/region/UNLOADED/FOV/超距/32-result 截断已通过；live entity query、sensor incomplete、线程错误、LOS 未验证。

## 6. 下一步

1. 继续剩余未验证项：第二客户端同步、INTERRUPTED 内部历史/queued-only removal、编程异常路径、chest/container 兼容性、sensor incomplete/LOS、live entity query。
2. 专用服务器（可选）：用户自行设置 `run/eula.txt` 或 `run/server/eula.txt` 的 `eula=true` 后运行 `runServer`；不要自动接受 EULA。
3. 若要恢复原始工作区直接构建：需要用户在普通会话中修复该项目目录的 ACL/完整性标签；本次未修改。
4. 已通过的基础项与 edge 项不再重复列为待测；不建议为未验证项引入新架构。

## 7. 历史

| 日期 | 阶段 | 结果 |
|---|---|---|
| 2026-10-27 | Phase 0 | 需求与环境审计；发现 `SPEC.md` 与 `PROGRESS.md` 角色冲突 |
| 2026-10-27 | Phase 0.1 | 文档归档与角色规范化；冻结 Self-Contained 目标；产出环境蓝图 |
| 2026-10-27 | P1-T1a | 便携 JDK 25 + Gradle 9.6.0 落地并校验 |
| 2026-10-27 | P1-T1b | Wrapper 相对本地发行版验证 + 最小 Fabric 脚手架 |
| 2026-10-27 | P1-T1c | 依赖解析与部分捕获；构建被沙箱 zipfs 只读阻断 |
| 2026-10-27 | P1-T1c-fix | 诊断确认 ZipFS `Files.isWritable` 失真；停止并请求执行上下文 |
| 2026-10-27 | Gate 2 | 实体 `swarmie:swarmie`、注册、客户端渲染器/模型/纹理；静态编译通过，运行未验证 |
| 2026-10-27 | Gate 3 | 三轴飞行控制、服务端 move 集成、碰撞轴处理、`/swarmie` 命令；静态编译通过，运行未验证 |
| 2026-10-27 | Gate 4 | per-robot Runtime、状态机、FIFO/取消/历史、timed-flight/stop、调试命令；静态编译与纯逻辑测试通过，运行未验证 |
| 2026-10-27 | Gate 5 | 9 槽库存、FakePlayer 交互、原生挖掘/放置、Runtime 交互命令；静态编译与纯逻辑测试通过，运行未验证 |
| 2026-10-27 | Gate 6 | 世界查询、局部传感、边界/视场/结果上限、查询/扫描命令；静态编译与纯几何测试通过，运行未验证 |
| 2026-10-27 | Runtime Verification Sprint | 初测 Low；授权 full-access 后 Medium/受限仍失败；用户普通副本构建成功，客户端进入单人世界；完成核心 smoke test；仅 dedicated server 未测 |
| 2026-10-27 | Regression Sprint | 持久化、FIFO/双队列、取消/恢复、交互边界/库存完整性、传感器实体/超距/截断实际通过；源码未改，JAR hash 不变 |
| 2026-10-27 | Edge-case Sprint | active removal、chunk unload/reload、failure isolation、chest 限制、lever、incomplete query 实际测试；源码/JAR 未改 |
| 2026-10-27 | GUI container 调查 | 确认 FakePlayer.openMenu no-op；`MenuProvider.createMenu + AbstractContainerMenu.clicked` 原生 slot 路径可行但未实现；影响所有标准 GUI 方块/实体，第三方自定义菜单不保证；源码/JAR 未改 |
| 2026-10-27 | README | 新增项目 README：架构、代码结构、命令、构建/验证、限制与文档索引；源码/JAR 未改 |
| 2026-10-27 | Adapter Prompt 1（旧版） | 早期 `adapter.contract` v1.0 草案；已被修订版独立 `:contract` 0.1 替代 |
| 2026-10-27 | Adapter Prompt 0A | Git 历史迁移到 `SwarmieRuntime`；原工作区备份；JUnit 5.13.1 本地依赖、39 项 JUnit 测试、`gradlew test` 与离线 build 通过 |
| 2026-10-27 | Adapter Prompt 1（修订版） | 独立纯 Java `:contract` 0.1、四命令最小契约、JAR 合并、边界检查与客户端启动 |
| 2026-10-27 | Adapter Prompt 2 | registry 三态、serverEpoch/1024 事件环、Runtime listener、卸载/维度/移除实测；命令 INTERRUPTED |
| 2026-10-27 | Adapter Prompt 3 | 有界入站 256/预算 32、correlation 幂等 1024、batch 64、END tick 事件 bus；Adapter submit/重复/冲突/事件顺序实测 |
| 2026-10-27 | Adapter Prompt 4 | 四命令规格、副作用/取消/容器限制、39 项测试、最终客户端回归与测试矩阵 |
| 2026-10-28 | Adapter Prompt 3.5 | 公共 cancel/TTL/随机 epoch；localhost JSONL（默认关闭，127.0.0.1）；Python mine/fly/stop/duplicate/cancel/reconnect 实机；JAR 162,555 bytes `fe3007e7…` |
| 2026-10-28 | Public release 0.1.0 | GitHub 公开仓库、MIT、SemVer 规范、CHANGELOG、CI、Release 资产准备；匿名化机器绝对路径 |




