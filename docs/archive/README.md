# 归档说明（docs/archive）

本目录保存 **2026-10-27（Phase 0.1）文档规范化之前**的原始文件，仅用于审计、追溯与恢复。
**不要编辑本目录中的任何文件。**

## 归档清单

| 归档文件 | 原始路径 | 原始大小 | 原始修改时间 | SHA-256 |
|---|---|---|---|---|
| `SPEC.phase0-brief.original.md` | `SPEC.md` | 6,300 B | 2026-10-27 02:40:00 +08:00 | `307b274c3589f47834a2156d606ff18c38b82acd0b710c0738f83d1306e6c956` |
| `PROGRESS.development-spec.original.md` | `PROGRESS.md` | 36,551 B | 2026-10-27 02:34:30 +08:00 | `d8c45cd4ab45cfed9fa2b95643921cdd181fbe0ec2504f32f7afa49fa211ac0b` |

两个归档副本均已用 SHA-256 与字节长度双重校验，与原文件逐字节一致。

## 规范化后的角色映射

| 当前路径 | 内容来源 | 关系 |
|---|---|---|
| `SPEC.md` | `PROGRESS.development-spec.original.md` | **逐字节复制**（哈希相同） |
| `docs/phase-0-brief.md` | `SPEC.phase0-brief.original.md` | **逐字节复制**（哈希相同） |
| `PROGRESS.md` | 新写的状态跟踪文件 | 不再保存规范正文；原文仅存于本目录 |
| `docs/archive/` | 两个原始文件 | 只读归档 |

## 说明

- `docs/phase-0-brief.md` 第 7 行仍保留“Read `SPEC.md` as the authoritative project specification”这句历史原文，未做任何修改。规范化后 `SPEC.md` 已是开发规范，因此该句在新角色下恰好仍然成立，无需改动。
- 若需恢复原始状态（仅文档操作，需显式授权）：
  - `Copy-Item docs/archive/SPEC.phase0-brief.original.md SPEC.md -Force`
  - `Copy-Item docs/archive/PROGRESS.development-spec.original.md PROGRESS.md -Force`
- 校验命令：`Get-FileHash .\docs\archive\*.md -Algorithm SHA256`，或使用 `docs/archive/SHA256SUMS.txt`。
