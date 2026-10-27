# Swarmie Handoff

## 1. 工作区

唯一活动源码/构建/测试位置：

```text
<workspace>
branch: main
remote: https://github.com/RrkwM/Swarmie
wiki: https://github.com/RrkwM/Swarmie/wiki
CI: GitHub Actions `.github/workflows/ci.yml` (JDK 25 + Gradle test/build)
license: MIT
version: 0.1.0
```

公开 clone 与构建：

```bash
git clone https://github.com/RrkwM/Swarmie.git
cd Swarmie
./gradlew test
./gradlew build
```

原工作区 `<original-workspace-backup>` 仅作为只读备份，
不再同步、构建或修改。不要恢复其 ACL/ZipFS 问题。

最近检查点：

```powershell
cd <workspace>
git log -1 --oneline
git status --short
```

## 2. 构建与测试

使用项目内 JDK/Gradle，不要升级或改系统环境：

```powershell
cd <workspace>
$env:JAVA_HOME = (Resolve-Path .\tools\jdk).Path
$env:GRADLE_USER_HOME = (Resolve-Path .\tools\gradle-home).Path
.\gradlew.bat test
.\tools\scripts\build.ps1
```

可选（仅开发验证）直接加载测试世界：

```powershell
.\gradlew.bat runClient -PswarmieQuickPlay="New World"
```

该属性只影响 `runClient` 的程序参数，不改变生产构建或 JAR。

当前验证结果：

```text
43 JUnit tests passed
BUILD SUCCESSFUL
```

产物：

```text
build/libs/swarmie-0.1.0.jar
162,555 bytes
SHA-256 fe3007e7df6ba694e6291149f49b50ae48cedabfa2946e56c42f9d0502a34f9a
```

基线 JAR：

```text
docs/verification/swarmie-baseline-dd529fd8.jar
SHA-256 dd529fd89192ff6f581fbcda9a5255d4945be790ef40a9b09202a57b7854861c
```

## 3. 目录约定

| 路径 | 说明 |
|---|---|
| `contract/` | 独立纯 Java `:contract`，无 Minecraft/Fabric 编译依赖 |
| `src/main/java/dev/swarmie/adapter/` | registry、event、io、transport |
| `src/test/` | 主 Mod JUnit；`contract/src/test/` 为契约测试 |
| `docs/` | 契约、生命周期、I/O、命令规格、测试矩阵、传输文档 |
| `run/` | 本地 disposable 测试世界/配置，gitignored |
| `tools/repo/` | 本地 Maven 依赖，gitignored |
| `tools/provision-tmp/` | 临时探针/测试缓存，gitignored |

## 4. Localhost JSONL（默认关闭）

源码默认不监听端口。启用时在 `run/config/swarmie-transport.json`：

```json
{
  "enabled": true,
  "port": 25580,
  "maxConnections": 4,
  "maxLineBytes": 8192,
  "maxOutboundEvents": 256
}
```

重启客户端后日志应出现：

```text
Local JSONL transport listening on 127.0.0.1:25580
```

Python 客户端（无第三方依赖）：

```powershell
python tools/client/swarmie_client.py --host 127.0.0.1 --port 25580
```

当前构建环境如无系统 Python，可使用 bundled Python：

```text
<bundled-python>
```

安全边界：仅 loopback、无认证/TLS、无远程/公网/多人安全模型。测试完成后删除
`run/config/swarmie-transport.json`，恢复正常默认关闭状态。

## 5. 已验证

- `:contract` 独立 classpath / boundary check；
- 43 项 JUnit；
- 主 JAR 包含契约类；
- 真实客户端启动/进入世界；
- 加载/卸载/重载、UUID 不变；
- 维度切换不产生 `REMOVED`；
- KILLED → `REMOVED`；
- active/queued 命令卸载中断；
- Adapter submit/duplicate/conflict；
- active cancel、unknown/terminal cancel；
- Python mine/fly/stop/duplicate/cancel/reconnect；
- 石头 `useItemOn` 放置、库存消耗；
- 随机 `serverEpoch` 会话变化。

## 6. 未验证/已知限制

- 事件环 1024 实机溢出未填满；小容量 JUnit 覆盖；
- batch 无实机命令；JUnit 覆盖逐条/上限；
- 第二客户端、专用服务器、GUI 容器、LOS；
- 高速/斜向/台阶/天花板碰撞；
- 慢消费者断连行为主要为代码审查/队列设计；
- localhost transport 没有正式协议/认证/远程支持。

## 7. 交接优先阅读

1. [`README.md`](README.md) — 项目入口、构建、约束；
2. [`docs/test-matrix.md`](docs/test-matrix.md) — 证据矩阵；
3. [`docs/adapter-contract.md`](docs/adapter-contract.md) — 0.1 契约；
4. [`docs/adapter-registry.md`](docs/adapter-registry.md) — 生命周期/serverEpoch/事件环；
5. [`docs/adapter-io.md`](docs/adapter-io.md) — 入站/出站/幂等/批量/取消；
6. [`docs/adapter-command-specs.md`](docs/adapter-command-specs.md) — 四命令语义；
7. [`docs/local-transport.md`](docs/local-transport.md) — 本地 JSONL/Python。

## 8. 下一阶段建议

- 若需要正式外部控制，先将 localhost JSONL 保持为调试层，另做有认证/有界发送队列的
  传输；不要把调试端口暴露到公网。
- 先补事件环实机溢出、批量实机提交、GUI 容器/第二客户端测试；
- 任何新能力都先定义契约和失败/取消语义，再实现；不要绕过 Runtime 或伪造世界副作用。




