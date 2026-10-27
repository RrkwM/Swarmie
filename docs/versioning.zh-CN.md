[English](versioning.md) | 简体中文

# 版本规范

Swarmie 遵循 [Semantic Versioning 2.0.0](https://semver.org/)。

## 当前版本线

```text
0.1.0
```

`0.y.z` 是初始开发阶段：公共 Adapter 契约和命令语义仍可能变化；尚未承诺
1.0 兼容性。

Adapter 契约有独立的 `ContractVersion`（`0.1`），与 Minecraft mod 版本分别
版本化。契约变化必须写入 changelog 和契约文档。

## 版本规则

### 1.0 之前

| 变化 | 版本 |
|---|---|
| 破坏公共 API/契约/命令语义 | 升 `MINOR`（`0.y.0`） |
| 向后兼容的新能力/行为 | 升 `PATCH`（`0.y.z+1`） |
| Bug 修复 | 升 `PATCH` |
| 无公共行为变化的内部重构 | 可不升版本 |

`0.x` 明确不稳定，消费者应固定精确版本并阅读 changelog。

### 1.0 之后

| 变化 | 版本 |
|---|---|
| 破坏公共 API | `MAJOR` |
| 向后兼容的新功能 | `MINOR` |
| 向后兼容的修复 | `PATCH` |

预发布使用 `-alpha.N`、`-beta.N`、`-rc.N`。已发布产物不得带 `SNAPSHOT`；
开发构建可以带。

## 版本来源

| 项目 | 来源 |
|---|---|
| Mod 版本 | `gradle.properties` → `mod_version` |
| 契约版本 | `ContractVersion.CURRENT` |
| Minecraft/Fabric 版本 | `gradle/libs.versions.toml` |
| 发布说明 | `CHANGELOG.md` + GitHub Release |
| 构建产物 | `build/libs/swarmie-<mod_version>.jar` |

## 发布检查清单

1. 把 `mod_version` 改为发布版本（去掉 `-SNAPSHOT`）；
2. 在 `CHANGELOG.md` 写带日期的条目和破坏性变更/已知限制；
3. 运行 `./gradlew test build` 和代表性真实客户端检查；
4. 确认 contract 边界和 JAR 内容；
5. 提交、推送 `main`，等待 GitHub Actions 成功；
6. 创建 annotated tag `vX.Y.Z`；
7. 从 tag 创建 GitHub Release，附 JAR 和 SHA-256；
8. 在契约稳定前，把 `0.y.z` 标为 pre-1.0；
9. 发布完成后再把 `mod_version` 升到下一个 `-SNAPSHOT`。

## 兼容性矩阵

```text
Minecraft 26.3
Fabric Loader 0.20.1
Fabric API 0.165.0+26.3
Java 25
```

其中任何一项变化都属于兼容性事件，必须记录，即使 Swarmie API 未变。

