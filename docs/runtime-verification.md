# Runtime Verification — 2026-10-27

> 状态：**真实 Fabric 构建成功；开发客户端成功到达主菜单并进入单人世界；
> 2026-10-27 regression sprint 已实际执行 persistence、FIFO、取消、交互边界和
> 传感器测试；同日完成 high-risk edge-case 测试（entity removal、chunk unload、
> failure isolation、container、incomplete query）。**
>
> 2026-10-27 Prompt 1（修订版）更新：建立独立纯 Java `:contract` 0.1。本文的
> 运行证据对应基线 JAR（79,806 bytes，`dd529fd8…`），该基线已保留在
> `docs/verification/swarmie-baseline-dd529fd8.jar`。当前 contract build 为
> 100,783 bytes，`895aeac8…`；客户端启动日志确认 `Adapter contract loaded: v0.1`。
> 世界内行为证据仍对应基线 JAR，本轮未重跑世界内测试。
> 唯一活动工作区已迁移到 `<workspace>`；原工作区为只读备份。
> 专用服务器尚未测试（无 EULA 接受）。

## 1. 执行上下文

- 构建/客户端会话：从桌面启动的普通 PowerShell 7，Medium Integrity，非管理员。
- 原始工作区 `<original-workspace-backup>` 仍带 DSH
  Low/受限 ACL，`Files.isWritable=false`，ZipFS 只读。
- 用普通用户复制到 `<workspace>` 后，ContextProbe 通过：

```text
DIR_FS_READONLY=false
PLAIN_WRITE=ok
PLAIN_IS_WRITABLE=true
PARENT_IS_WRITABLE=true
ZIPOUT_WRITE=ok
ZIPFS_CREATE_READONLY=false
ZIPFS_CREATE_WRITE=ok
CONTEXT_OK
```

- 未修改原始工作区 ACL/完整性标签；副本是同一源码/版本的验证环境。
- 未使用管理员、未修改系统设置、未绕过沙箱。

## 2. 工具链与构建

| 组件 | 实际值 |
|---|---|
| JDK | Temurin 25.0.4.1+1（项目内） |
| Gradle | 9.8.2（项目内 wrapper） |
| Loom | 1.19.3 |
| Minecraft | 26.3 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.165.0+26.3 |

构建命令（脚本内部）：

```text
gradlew.bat --no-daemon --console=plain --stacktrace
  -I tools/bootstrap/online-repos.init.gradle.kts
  -Dorg.gradle.java.home=<project>/tools/jdk
  -Dorg.gradle.java.installations.paths=<project>/tools/jdk
  build
```

实际结果：

```text
Fabric Loom: 1.19.3
> Task :compileJava
> Task :processResources
> Task :compileClientJava
> Task :jar
> Task :build
BUILD SUCCESSFUL in 43s
6 actionable tasks: 6 executed
```

构建日志：`<workspace>\tools\logs\verify-runtime-20261027-070609.log`
（副本内）。

## 3. 有效 Mod JAR

| 项目 | 值 |
|---|---|
| 路径（项目内） | `build/libs/swarmie-0.1.0-SNAPSHOT.jar` |
| 构建位置 | `<workspace>\build\libs\swarmie-0.1.0-SNAPSHOT.jar` |
| 大小 | **79,806 bytes** |
| SHA-256 | `dd529fd89192ff6f581fbcda9a5255d4945be790ef40a9b09202a57b7854861c` |
| Mod ID | `swarmie` |
| Mod version | `0.1.0-SNAPSHOT`（`${version}` 已展开） |
| main entrypoint | `dev.swarmie.SwarmieMod` |
| client entrypoint | `dev.swarmie.client.SwarmieClient` |
| ZIP 完整性 | 通过；59 个条目 |

包含：实体、飞行控制、Runtime、命令、库存、InteractionActor 及 FakePlayer、
query、sensor、客户端 entrypoint/模型/渲染器、纹理与中英文语言文件。
旧 1,666-byte 无效 JAR 已被替换。

## 4. 客户端启动

- `runClient` 实际启动成功。
- Minecraft 窗口标题先为 `Minecraft* 26.3`，到达主菜单；
- 进入已有一次性测试世界 `New World`（Creative、Commands）；
- 窗口标题变为 `Minecraft* 26.3 - Singleplayer`，进程响应正常。
- 主菜单截图：[main-menu.png](verification/main-menu.png)
- 实体渲染截图：[summoned.png](verification/summoned.png)

启动日志（节选）：

```text
Swarmie initialized; registered entity type swarmie:swarmie
Backend library: LWJGL version 3.4.3-snapshot
Created window using SDL video driver: windows
Sound engine started
Starting integrated minecraft server version 26.3
Player125[...] logged in ...
```

客户端有非致命的 `Class path entries reference missing files:
build/resources/client` 警告，未阻止启动或运行。

## 5. 实际游戏内 smoke test

| # | 测试 | 实际结果 |
|---|---|---|
| 1 | Summon | `/summon swarmie:swarmie ~ ~1 ~` → `Summoned new Swarmie` |
| 2 | 渲染 | 近景截图可见蓝色/橙色无人机模型；纹理正常 |
| 3 | X 轴 | `fly ... 0.2 0 0 20` → 机器人向右移动到水面上方；#1 SUCCEEDED |
| 4 | Y 轴 | `fly ... 0 0.2 0 20` → 机器人升空；#2 SUCCEEDED |
| 5 | Z 轴 | `fly ... 0 0 0.2 20` → 机器人横向移动；#3 SUCCEEDED |
| 6 | Stop | `swarmie stop ...` → #4 STOP SUCCEEDED |
| 7 | 碰撞 | 石墙在 z=4；机器人从 z=2.5 飞向 +Z 后停在 **z=3.6**，未穿墙 |
| 8 | Runtime | `status` → `current=none; queued=0; retained=4`；`results` 显示 #1–#4 成功 |
| 9 | 库存 | 插入铁镐，`inventory show` → `selected=0: iron_pickaxe x1` |
| 10 | 挖掘 | `mine ... -20 80 4` → 方块变为 `minecraft:air`；命令 #6 SUCCEEDED；NBT 显示铁镐 `damage=1` |
| 11 | 放置 | 石头 8 个；`use ... -20 78 2 up` → -20,79,2 变为 stone；数量 8→7；#7 SUCCEEDED |
| 12 | 方块查询 | 空气/石头均正确；远处 `10000 64 10000` 返回 `UNLOADED: chunk is not loaded` |
| 13 | 区域查询 | 8 个体积，`complete=true`，方块计数与坐标正确 |
| 14 | 传感器 | yaw=0、正前方 2.2 格石头被检测；把石头移到背后后 blocks=0（FOV 排除） |

关键日志：

```text
[07:14:22] #1 TIMED_FLIGHT SUCCEEDED (duration 20 ticks)
[07:14:55] Swarmie current=none; queued=0; retained=4
[07:14:56] #4 STOP SUCCEEDED (stopped)
[07:15:32] entity data: [-19.5d, 80.0d, 3.5999999940395355d]
[07:15:39] Inserted 1 x minecraft:iron_pickaxe
[07:15:53] Block ...: minecraft:air air=true
[07:16:08] {components: {"minecraft:damage": 1}, ... iron_pickaxe}
[07:16:28] Block ... y=79 ...: minecraft:stone air=false
[07:16:29] inventory: *[0] minecraft:stone x7
[07:16:39] Region ... volume=8 blocks=8 unavailable=0 complete=true
[07:16:54] Sensor ... blocks=1 ... stone@2.2
[07:17:08] Sensor ... blocks=0
[07:17:10] #6 MINE_BLOCK SUCCEEDED | #7 USE_ITEM_ON_BLOCK SUCCEEDED
[07:17:11] Block query UNLOADED: chunk is not loaded
```

航向修复说明：实体 summon 后的默认朝向不朝向测试方块，第一次 sensor 返回 0；
显式 `tp ... 0 0` 设置 yaw/pitch 并放置正前方方块后检测成功，属于测试设置问题，
不是传感器缺陷。

命令 selector 注意：必须使用完整 ID：

```mcfunction
@e[type=swarmie:swarmie,limit=1]
```

`@e[type=swarmie]` 会被解析成 `minecraft:swarmie` 并报 unknown entity type。
文档中的示例已全部修正。

## 6. 专用服务器

**未测试。** `run/eula.txt` 与 `run/server/eula.txt` 均不存在；没有代替用户
接受 EULA。客户端 integrated server 已证明 common 入口/命令注册能在服务端
逻辑侧加载，但这不等于 dedicated server 验收。

## 7. 已知限制 / 未验证

- 原始工作区仍不能直接 build（DSH ACL/Low label）；必须先复制到普通目录。
- 单客户端；第二客户端同步未验证；区块卸载/重载已通过（见 §10）。
- Runtime：FIFO、active/queued/cancelall、恢复、正常失败隔离已通过；异常隔离
  （编程异常）、实体移除后的 INTERRUPTED 内部状态、跨重启队列未验证。
  active cancel 后的**定量减速曲线**未测量（只验证了 CANCELLED 状态和状态收敛）。
- 交互：普通挖掘、不可破坏方块、放置、空气/未加载、失败库存完整性、打火石、
  lever 已通过；chest 的 `useItemOn` 返回 SUCCESS 但 chest 内容不变、无异常，
  FakePlayer 无连接导致容器未被实际打开；保护区域、第三方模组钩子未验证。
- 传感器：方块/实体前后、距离、32-result 截断已通过；incomplete 传感器、LOS
  （明确未支持）未验证。WorldQuery 的 entity-query API 没有 live command，
  live entity query 未验证。
- 专用服务器未测试；EULA 未接受。
- 原 JAR 从 clean copy 复制回项目；`build/libs` 的 JAR 是有效的，但原始工作区
  直接 `build` 仍会失败。

## 8. 下一步

1. 专用服务器（可选）：用户自行在 `run/eula.txt` 或 `run/server/eula.txt` 写入
   `eula=true` 后，运行 `runServer`；不要由脚本自动接受。
2. 继续 Gate 2–6 的完整验收项（第二客户端、区块卸载/重载、Runtime 异常/移除、
   交互保护、LOS、实体查询）。
3. 若要恢复原始工作区直接构建：需要用户在普通会话中修复该项目目录的 ACL/
   完整性标签；这属于环境修复，当前未执行。

## 9. Regression sprint — 2026-10-27

测试工作区：`<workspace>`，源码与项目工作区逐文件 SHA-256
一致；JAR 未变化。测试世界：`New World`（Creative + Commands，一次性）。

### 9.1 Test A — Entity / inventory persistence

前置：已有 Swarmie，UUID `[I; -1903784505, -743488341, -1274459322, 612522765]`，
位置 `[-19.5, 80.0, 3.6]`，库存 `stone x7, dirt x5, apple x3`，selected=0。

命令（实际使用完整 ID）：

```mcfunction
/swarmie inventory give @e[type=swarmie:swarmie,limit=1] minecraft:dirt 5
/swarmie inventory give @e[type=swarmie:swarmie,limit=1] minecraft:apple 3
/swarmie inventory show @e[type=swarmie:swarmie,limit=1]
```

然后通过原版 Esc 菜单退出到标题画面（实际观察到 `Minecraft* 26.3` 标题界面），
重新进入同一世界。`/save-all` 与 `/stop` 在该单人环境不是可用命令。

实际结果：重载后 UUID、位置、三项 stack（ID/count/slot）和 selected=0 完全一致；
重载后 `fly ... 0.1 0 0 10` 成功（#1），`use ... -20 78 0 up` 成功（#2），
库存 stone 7→6。**PASS**。

（注：`/save-all` 与 `/stop` 在该单人环境不可用；使用原版菜单保存。）

### 9.2 Test B — FIFO ordering / barrier

前置：单机器人。先提交 `fly ... 0.1 0 0 100`，运行中提交
`fly ... 0 0.1 0 10`。

运行中的实际输出：

```text
Swarmie current=#3 TIMED_FLIGHT RUNNING; queued=1; retained=2
#1 ... | #2 ...
```

完成后：

```text
current=none; queued=0; retained=4
#3 TIMED_FLIGHT SUCCEEDED | #4 TIMED_FLIGHT SUCCEEDED
```

第一命令运行期间第二命令保持 queued；第一完成前 results 不含 #3/#4；完成后
按 #3→#4 顺序出现。**PASS**。

双机器人：tag A/B，A `fly ... 0.1 0 0 100`，B `fly ... 0 0.1 0 20`。
实际观察到 A `current=#5 RUNNING` 时 B 已 `current=none`；A 结果 #1–#5、
B 结果 #1–#2 独立。**PASS**。

### 9.3 Test C — Cancellation

| 场景 | 实际结果 |
|---|---|
| Active cancel | #6 长飞行 `cancel` → `current=none`，#6 `CANCELLED (cancelled while active)` |
| Queued cancel by ID | #7 运行、#8 queued；`cancelid ... 8` → `queued=0`，later #8 `CANCELLED (cancelled while queued)` |
| Cancel all | #9/#10 queued/running；`cancelall` → `Cancelled 2 command(s)`，#9/#10 CANCELLED |
| Recovery | #11 短飞行 → `#11 TIMED_FLIGHT SUCCEEDED` |

所有取消状态和“queued 不启动”均符合文档。active cancel 后只验证了状态收敛，
未做 velocity/位置曲线的定量减速测量；该子项 **UNVERIFIED**。整体取消测试
**PASS**。

### 9.4 Test D — Interaction boundaries / inventory integrity

| 测试 | 实际结果 |
|---|---|
| 普通方块挖掘（铁镐） | `-20 80 4` → air；#6 MINE SUCCEEDED；NBT `iron_pickaxe damage=1` |
| 不可破坏方块 | B 对 bedrock 的 `mine` → `FAILED: block is unbreakable`；库存 stone 5 不变 |
| 普通放置 | A 石头 8→7；重载后再放置 7→6 |
| 无效目标（空气） | B `use` 空气 → `FAILED: target is air`；库存 5 不变 |
| 未加载区块交互 | B `use` 10000,64,10000 → `FAILED: target chunk is not loaded`；库存 5 不变 |
| 非放置原生交互 | B 打火石点黑曜石 → #6 USE SUCCEEDED，`-25 81 7` 变为 `minecraft:fire`，打火石 `damage=1` |

失败操作未发现物品复制或丢失。保护区域、容器/复杂方块和第三方模组钩子
**UNVERIFIED**；未修改任何保护配置。

### 9.5 Test E — Sensor behavior

前置：B 在 `(-24.5, 80, 3.5)`，yaw=0/pitch=0（面向 +Z）；方块/牛在前后放置。

实际输出：

```text
front block/cow: Sensor ... blocks=2 entities=1 ... stone/bedrock ... cow@2.0
front cow killed: Sensor ... entities=0
far cow at z=20: Sensor ... entities=0
fill 163 blocks: Sensor ... blocks=32 entities=0 truncated=true incomplete=false
```

- 正前方方块/实体检测：**PASS**
- 背后方块/实体被 90° FOV 排除：**PASS**
- 超出 8 格范围的牛不检测：**PASS**
- 32 结果上限触发 `truncated=true`：**PASS**
- 未加载区域 `incomplete` / LOS / 实体查询：**UNVERIFIED**（现有命令/环境无法可靠构造；
  LOS 本身标记为 unsupported）。

### 9.6 结论

- 源码：**未修改**（仅文档与验证脚本）。
- 构建：无新构建；有效 JAR 仍为
  `build/libs/swarmie-0.1.0-SNAPSHOT.jar`，79,806 bytes，
  SHA-256 `dd529fd89192ff6f581fbcda9a5255d4945be790ef40a9b09202a57b7854861c`。
- 回归测试实际通过：persistence、FIFO/barrier、active/queued/cancelall/recovery、
  交互成功/失败库存完整性、传感器前后/距离/截断。
- 仍未验证：第二客户端、区块卸载/重载、Runtime 异常/实体移除、保护/容器交互、
  LOS/未加载传感器、dedicated server。

## 10. High-risk edge cases — 2026-10-27

同一工作区/同一 JAR（`dd529fd8…`），单客户端 disposable world。所有命令使用
完整 selector `@e[type=swarmie:swarmie,...]` 或 tag。

### 10.1 Test A — Entity removal / Runtime interruption

前置：A 在 `(41.747, 81.819, 3.6)`，`current=none; retained=11`。

命令：

```mcfunction
/swarmie fly @e[tag=A,limit=1] 0.2 0 0 200
/swarmie fly @e[tag=A,limit=1] 0 0.2 0 20
/swarmie status @e[tag=A,limit=1]
/kill @e[tag=A,limit=1]
```

实际：

```text
current=#12 TIMED_FLIGHT RUNNING; queued=1; retained=11
Killed Swarmie
```

`/execute if entity @e[tag=A,limit=1] run say A_STILL_PRESENT` 无输出；A 不再存在。
随后 summon C：`current=none; queued=0; retained=0`，`No retained command results`。
**PASS**（可观察的 lifecycle 清理）。

未验证：被移除对象的内部 `INTERRUPTED` 历史（Runtime 随实体销毁，无 surviving
store）；“只有 queued 没有 active” 的状态不通过公开命令可达（提交后下一 tick
即启动），**UNVERIFIED**。

### 10.2 Test B — Chunk unload / reload

前置：C 在 `(500.5,100,500.5)`，UUID
`[I; -105031719, 1678262328, -1958305683, -1839583651]`，库存 stone4/dirt2。

把玩家传送到 `(1500,100,1500)`，等待 12s：

```text
No entity was found
```

返回 `(500,100,500)` 后：

```text
UUID = [I; -105031719, 1678262328, -1958305683, -1839583651]
Pos = [500.5, 100.0, 500.5]
inventory = *[0] stone x4 [1] dirt x2
```

`execute as @e[tag=C] run data get ... UUID` 只输出一行（无重复实体）。重载后
`fly ... 0.1 0 0 10` 与
`use @e[tag=C,limit=1] 500 100 505 up` 均成功；石头 4→3。无 stale interaction
context 异常。**PASS**。

### 10.3 Test C — Failure isolation

C 对 bedrock：

```text
#3 MINE_BLOCK FAILED (block is unbreakable)
#4 TIMED_FLIGHT SUCCEEDED
```

独立 E：

```text
#1 TIMED_FLIGHT SUCCEEDED
```

失败后同一 Runtime 继续；另一机器人不受影响。**PASS**。
未验证：真正的编程异常路径（公共命令不能安全注入）；纯逻辑测试已覆盖。

### 10.4 Test D — Native container

Chest 在 `(500,100,503)`，内容 diamond x3。C 在范围内执行：

```mcfunction
/swarmie use @e[tag=C,limit=1] 500 100 503 north
```

实际：

```text
#7 USE_ITEM_ON_BLOCK SUCCEEDED
chest data = [{count: 3, Slot: 0b, id: "minecraft:diamond"}]
C inventory = stone x3 [1] dirt x2
```

`useItemOn` 返回 SUCCESS，但 chest 内容未变、C 物品未变，服务器日志无异常。
这是 **FakePlayer 无 network connection，容器未被实际打开/操作** 的兼容性限制，
不是物品复制。**PASS（限制已实证；容器功能未生效）**。

#### 10.4.1 为什么“交互成功”但 chest 没变化

这不是 Runtime 把失败误报成功，也不是 chest 应该“放入物品”：

1. `ServerPlayerGameMode.useItemOn(...)` 在非潜行、手持物品路径下调用
   `BlockState.useItemOn(...)`；chest 返回 `TryEmptyHandInteraction`，随后调用
   `BlockState.useWithoutItem(...)`。只要结果 `consumesAction()` 为 true，就原样
   返回该 `InteractionResult`。
2. `ChestBlock.useWithoutItem(...)` 在服务端：
   - 取 `MenuProvider`；
   - 调用 `Player.openMenu(provider)`；
   - **无条件返回 `InteractionResult.SUCCESS`**，不检查菜单是否真的打开。
3. Fabric `FakePlayer` 明确覆盖了菜单路径：
   - 构造函数把 `connection` 设为 `null`；
   - `openMenu(MenuProvider)` 直接返回 `OptionalInt.empty()`；
   - `openTextEdit`、`openHorseInventory` 为空实现，`awardStat` 也为空实现。
4. 因此 chest 的 `openMenu` 调用是 no-op：不创建 `ChestMenu`，没有玩家可见容器，
   chest 内容当然不变；但 block use 仍返回 SUCCESS，Runtime 记录 SUCCESS。
5. **即便换成真实玩家，打开 chest 也不会自动“把物品放入”**。放入物品需要
   `AbstractContainerMenu` 的 slot 点击/服务器菜单操作；本测试只验证了“打开容器”
   路径，而 FakePlayer 正好不支持它。没有任何物品复制或丢失。

结论：这是 **container/menu 交互不支持的明确兼容性限制**，不是可修复的
Runtime/InteractionActor bug。支持它需要伪造 `ServerGamePacketListenerImpl`/
菜单同步或另建容器适配器，属于新的网络/交互架构，当前 sprint 明确不做。
不需要修改源码。

#### 10.4.2 影响范围与“服务端 slot click”调查

影响的不只是 chest。所有通过 `MenuProvider` / `AbstractContainerMenu` 暴露
slot 的 GUI 方块或实体都属于同一类：

- block：`BlockState.getMenuProvider(Level, BlockPos)` 返回 `MenuProvider`；
  `MenuProvider.createMenu(id, Inventory, Player)` 创建菜单；标准 GUI 方块
  （chest、furnace、hopper、crafting table 及大量第三方模组机器）都走这里。
- entity：实现 `MenuProvider` 的实体可提供菜单；原版 horse/villager 等使用
  专用 `openHorseInventory`/`mobInteract` 路径，不统一暴露 `MenuProvider`。
- FakePlayer 对 `openMenu`、`openHorseInventory` 都做了 no-op，因此这条
  通用 GUI 打开路径整体不可用。

“非模拟”的服务端路径确实存在，但需要自己搭菜单：

```text
BlockState.getMenuProvider(...)
→ MenuProvider.createMenu(...)
→ AbstractContainerMenu.setSynchronizer(no-op ContainerSynchronizer)
→ menu.stillValid(player)
→ menu.clicked(slot, button, ContainerInput.PICKUP/QUICK_MOVE, player)
→ menu.removed(player)
```

`AbstractContainerMenu.clicked(...)` 会执行原版 `doClick` 的 slot 逻辑，移动
`Slot`/`Container` 中的真实 `ItemStack`；这不是直接改世界/伪造物品。原版真实
客户端路径是 `ServerGamePacketListenerImpl.handleContainerClick(...)`：校验
`player.containerMenu`、containerId、`stillValid`，调用 `clicked`，并处理
remote slot/carried 同步。

但这条路径不是一个现成的通用 API：

- 必须先取得 `MenuProvider` 并知道菜单的 slot 编号（block slots + player
  inventory 的位置）；不同菜单/模组可能不同。
- 需要处理 `stillValid`、访问权限、菜单生命周期和 `ContainerSynchronizer`
  的 no-op/同步语义；不能简单把 `clicked` 当成 `useItemOn`。
- 原版 horse/villager 等实体没有统一 `MenuProvider`，要按实体类型特判；
  第三方模组的自定义 screen/network/data slot 也可能依赖客户端状态。
- 需要一个明确的“把玩家手中的哪个 stack 放到哪个槽”的策略，不能只凭
  `useItemOn` 的 SUCCESS 推断。

补充的 26.3 字节码证据：

- `ChestMenu` 有公开的 `threeRows(int, Inventory, Container)` 等工厂，可直接从
  `MenuProvider.createMenu(...)` 得到原生菜单；
- `AbstractContainerMenu.clicked(...)` 只调用私有 `doClick(...)`，其字节码没有
  引用 `synchronizer`、`connection` 或 `player.containerMenu`；slot 移动本身
  不依赖客户端连接；
- `ContainerSynchronizer` 只在 `broadcastChanges`/初始同步时使用；直接调用
  `clicked` 可配合 no-op synchronizer，但仍必须自行处理 `stillValid`、菜单
  生命周期和 `removed(...)`；
- 真实客户端路径 `handleContainerClick` 才会做 containerId/remote slot/carried
  校验；直接调用 `clicked` 必须由适配器补上这些保护。

因此“非模拟”的 server-side slot click 是**可行但未实现、未 live 验证**的；
它不是 Fabric 现成 API，也没有项目代码。对标准 `MenuProvider` block 可做窄
适配；对原版 horse/villager、第三方自定义 screen/network 菜单不能承诺通用。

项目目前 **没有任何** `MenuProvider`/`AbstractContainerMenu.clicked` 代码
（`src` 搜索无匹配）；`InteractionActor` 只有 `useItemOn`。因此当前实现不能
操作此类 GUI。若要支持，最小正确方向是新增一个窄的 **server-side menu
adapter**，明确只支持 `MenuProvider` block 菜单和固定 slot 策略，并用 chest/
furnace 做测试；不应尝试给 FakePlayer 伪造真实连接，也不应声称通用支持所有
模组 GUI。该新增能力超出本轮调查/测试范围，未实现。

Lever 在 `(500,100,504)`：

```text
before: powered=false
after:  powered=true
```

**PASS**（简单原生方块交互可用）。

### 10.5 Test E — World-query entity results

检查 `SwarmieCommands`：只有 `query block`、`query region`、`sensor scan`；
没有任何 live command 调用 `WorldQuery.queryEntities(...)`。没有新增测试专用
命令/backdoor。**UNVERIFIED**（API 存在，live interface 不存在）。

### 10.6 Test F — Incomplete query

玩家在 `(500,100,500)`，查询到 `1000`：

```text
/swarmie query block 500 101 500
→ Block ... minecraft:air air=true

/swarmie query block 999 100 500
→ Block query UNLOADED: chunk is not loaded

/swarmie query region 500 100 500 1000 100 500
→ volume=501 blocks=300 unavailable=201 complete=false

/swarmie query block 1000 100 500
→ UNLOADED
```

loaded air 成功、未加载位置明确 unavailable、区域 `complete=false`，且查询后
1000 仍未加载（没有隐式 chunk load）。**PASS**。

### 10.7 Edge-case 结论

- 源码未修改；JAR 仍为 79,806 bytes，SHA-256
  `dd529fd89192ff6f581fbcda9a5255d4945be790ef40a9b09202a57b7854861c`。
- 有未验证项：INTERRUPTED 内部历史、queued-only removal、异常路径、live entity
  query、sensor incomplete/LOS、第二客户端、dedicated server。

