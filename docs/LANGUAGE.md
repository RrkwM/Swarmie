# Documentation Language Policy

English | [简体中文](LANGUAGE.zh-CN.md)

**English is the default.** Every current public document has an English file at
its normal path. Simplified Chinese is available with a `.zh-CN.md` suffix (and
`-zh-CN` for Wiki pages).

## Public bilingual documents

| English (default) | 简体中文 |
|---|---|
| [`README.md`](../README.md) | [`README.zh-CN.md`](../README.zh-CN.md) |
| [`player-guide.md`](player-guide.md) | [`player-guide.zh-CN.md`](player-guide.zh-CN.md) |
| [`developer-guide.md`](developer-guide.md) | [`developer-guide.zh-CN.md`](developer-guide.zh-CN.md) |
| [`adapter-contract.md`](adapter-contract.md) | [`adapter-contract.zh-CN.md`](adapter-contract.zh-CN.md) |
| [`adapter-registry.md`](adapter-registry.md) | [`adapter-registry.zh-CN.md`](adapter-registry.zh-CN.md) |
| [`adapter-io.md`](adapter-io.md) | [`adapter-io.zh-CN.md`](adapter-io.zh-CN.md) |
| [`adapter-command-specs.md`](adapter-command-specs.md) | [`adapter-command-specs.zh-CN.md`](adapter-command-specs.zh-CN.md) |
| [`local-transport.md`](local-transport.md) | [`local-transport.zh-CN.md`](local-transport.zh-CN.md) |
| [`test-matrix.md`](test-matrix.md) | [`test-matrix.zh-CN.md`](test-matrix.zh-CN.md) |
| [`versioning.md`](versioning.md) | [`versioning.zh-CN.md`](versioning.zh-CN.md) |
| [`CHANGELOG.md`](../CHANGELOG.md) | [`CHANGELOG.zh-CN.md`](../CHANGELOG.zh-CN.md) |
| [`CONTRIBUTING.md`](../CONTRIBUTING.md) | [`CONTRIBUTING.zh-CN.md`](../CONTRIBUTING.zh-CN.md) |
| [Wiki Home](https://github.com/RrkwM/Swarmie/wiki) | [Wiki 中文首页](https://github.com/RrkwM/Swarmie/wiki/Home-zh-CN) |

## Language switches

Each pair starts with a language switch:

```text
[English](page.md) | 简体中文
```

Wiki pages use:

```text
English | [简体中文](Page-zh-CN)
```

## Historical / internal records

`SPEC.md`, `PROGRESS.md`, `HANDOFF.md`, `docs/runtime-verification.md`,
`docs/environment-plan.md`, `docs/testing.md`, `docs/gate*-notes.md`,
`docs/version-baseline.md`, `docs/provisioning-findings.md`,
`docs/phase-0-brief.md`, `docs/p1-t1c-fix-diagnosis.md` and `docs/archive/` are
maintainer/historical records. They are retained in their original Chinese and
are not part of the current public English documentation set. Current public
behavior, commands and limitations are defined by the bilingual documents above.

## Rules for contributors

- Public behavior changes update both language versions.
- Links in the English default point to English defaults.
- Links in `.zh-CN` documents point to `.zh-CN` versions where available.
- Wiki pages are mirrors; canonical versioned docs live in this repository.
- Do not publish only a Chinese version of a current public document.
