[English](Developer-Guide) | 简体中文

# 开发者指南

> Wiki 简短镜像。Canonical 文档：
> [docs/developer-guide.zh-CN.md](https://github.com/RrkwM/Swarmie/blob/main/docs/developer-guide.zh-CN.md)。

## 前置条件

JDK **25**、Git；首次构建需要网络下载 Gradle/Minecraft/Fabric 依赖。

## 构建与测试

```bash
git clone https://github.com/RrkwM/Swarmie.git
cd Swarmie
./gradlew test
./gradlew build
./gradlew runClient
```

Windows：

```powershell
.\gradlew.bat test
.\gradlew.bat build
.\gradlew.bat runClient
```

产物：`build/libs/swarmie-0.1.0.jar`。

## 结构

- `contract/` — 纯 Java 契约，不依赖 Minecraft/Fabric；
- `src/main/java/dev/swarmie/` — Entity、Runtime、交互、查询、传感器、命令、Adapter；
- `src/main/java/dev/swarmie/adapter/` — registry、events、入站/出站队列、
  localhost JSONL；
- `src/test/`、`contract/src/test/` — JUnit；
- `docs/` — 规范、测试矩阵和交接。

## Adapter

```java
SubmitAck ack = SwarmieAdapter.submit(
        robotId,
        new CommandSpec.Stop(),
        "correlation-id");
```

- `ACCEPTED` 不是执行完成；观察事件/终态；
- `cancel` 异步；终态为 `COMMAND_CANCELLED`；
- 事件 batch 包含 `serverEpoch`、`sequence`、`missed`；
- 服务器 tick 拥有实体/Runtime 访问权；外部线程只入队；
- 事件监听器必须快速、非阻塞。

继续阅读：

- [契约](https://github.com/RrkwM/Swarmie/blob/main/docs/adapter-contract.zh-CN.md)
- [注册表/生命周期](https://github.com/RrkwM/Swarmie/blob/main/docs/adapter-registry.zh-CN.md)
- [I/O 与幂等](https://github.com/RrkwM/Swarmie/blob/main/docs/adapter-io.zh-CN.md)
- [命令规格](https://github.com/RrkwM/Swarmie/blob/main/docs/adapter-command-specs.zh-CN.md)
- [版本规范](https://github.com/RrkwM/Swarmie/blob/main/docs/versioning.zh-CN.md)
- [测试矩阵](https://github.com/RrkwM/Swarmie/blob/main/docs/test-matrix.zh-CN.md)
