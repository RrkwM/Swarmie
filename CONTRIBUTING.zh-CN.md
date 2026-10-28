[English](CONTRIBUTING.md) | 简体中文

# 为 Swarmie 做贡献

## 开始之前

- Minecraft `26.3`、Fabric Loader `0.20.1`、Fabric API `0.165.0+26.3`；
- JDK **25**（推荐 Temurin）；
- 首次构建需要访问官方 Fabric/Maven 仓库；不需要项目内工具链；
- 不要提交 `tools/jdk`、`tools/repo`、`tools/gradle-home`、`run/`、`build/`。

## 本地开发

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

产物：`build/libs/swarmie-0.1.1-SNAPSHOT.jar`。

## 项目边界

- `contract/` 是纯 Java 子项目，不得引入 Minecraft/Fabric 依赖；
- `src/main/java/dev/swarmie/` 是 Minecraft/Fabric 实现；
- `src/test/` 与 `contract/src/test/` 是 JUnit；
- Adapter 契约、生命周期和命令规格在 `docs/`；修改公共行为前先更新规格。

## 测试

```bash
./gradlew test
```

当前基线：46 项 JUnit。测试矩阵在 `docs/test-matrix.md`，区分自动、真实客户端、
静态和未验证证据。不要把 JUnit 通过说成真实客户端通过。

## 代码与审查

- 除非同时提供测试过的替代方案，否则保持现有运动/碰撞/库存/交互/查询语义；
- Runtime 只负责调度，不直接访问世界；
- 不要把测试命令当作正式 API；
- 保持 localhost JSONL 默认关闭、仅 loopback，它不是远程安全模型；
- 新行为要有测试，或说明为什么只能手动验证；
- PR 保持聚焦，不要把格式变化和行为变化混在一起。

## 许可证

MIT，见 `LICENSE`。贡献即表示同意以 MIT 条款提供贡献。

