# Phase 0 — Requirements Baseline and Environment Audit

## Objective

Prepare the Swarmie Minecraft mod project for implementation.

The host OS is Windows 11 Enterprise. The target is Minecraft 26.3 + Fabric. Read `SPEC.md` as the authoritative project specification.

This is an audit and planning phase, not an implementation phase.

## Strict operating constraints

Until the audit is complete:

- Do not write or modify source code.
- Do not install, upgrade, or remove software.
- Do not download dependencies or run installers.
- Do not modify PATH, registry, system settings, execution policies, or global configuration.
- Do not request administrator privileges.
- Do not enable unrestricted permissions or bypass approval policies.
- Do not inspect unrelated personal files or collect credentials.
- Read-only inspection of the project workspace and relevant development-tool metadata is permitted.
- Read-only research using official documentation or source repositories is permitted if available. Do not execute downloaded code.
- If a check is impossible under these constraints, report it rather than attempting a workaround.

Do not assume that a task is complete merely because a tool or command was invoked successfully.

## Task 1 — Demonstrate understanding of the specification

Read `SPEC.md` and produce a structured requirements audit.

Identify:

1. The primary goal and the six development gates.
2. Non-negotiable architecture decisions.
3. Responsibilities of the entity body, Runtime, interaction context, and sensing capabilities.
4. What must reuse Minecraft's native mechanisms and what can be custom-built.
5. Explicitly deferred features.
6. Acceptance tests that can determine whether each gate is complete.

Separate direct requirements from your own implementation recommendations. Do not silently change or weaken a requirement.

## Task 2 — Audit the host environment

Inspect only the information relevant to this project.

Report the available state of:

- Windows edition, version, and architecture.
- PowerShell availability.
- Git and its version.
- Installed Java/JDK versions.
- Node.js and package-manager availability, if relevant to the existing Harness setup.
- Existing relevant development tools and environment variables.
- Available disk space on the intended project drive.
- Current workspace path and whether it already contains files.

Do not assume that the machine needs every tool to be installed. Distinguish missing tools from tools that have not yet been checked.

Do not expose secrets, API keys, tokens, or unrelated environment-variable values.

## Task 3 — Define what Self-Contained means for this project

Evaluate these three distinct deployment targets:

**A. Self-contained project workspace**

Project configuration, scripts, and build instructions live in the project directory. The project avoids user-specific absolute paths and unpinned dependency versions. Initial dependency retrieval may still require network access.

**B. Reproducible, portable build environment**

The project uses pinned tool versions and a controlled dependency cache. Builds can be reproduced on another compatible Windows machine. Network access may be needed during the initial bootstrap.

**C. Fully offline / air-gapped build**

After the environment has been provisioned, a clean build must succeed without network access. The complete required toolchain, Gradle distribution, Minecraft/Fabric artifacts, plugins, transitive dependencies, and other necessary assets must already be available locally.

For each target, explain its prerequisites, limitations, verification method, and maintenance cost.

Do not redefine “absolutely no external references” to mean something weaker without explicitly saying so. In particular, distinguish external runtime services, source-code dependencies, build-time artifact retrieval, and offline-build requirements.

Recommend a concrete interpretation for Swarmie, but do not implement it yet.

## Task 4 — Verify target-version feasibility

Determine what can currently be verified about the Minecraft 26.3 + Fabric development toolchain.

Check relevant official documentation and source repositories where possible. Identify:

- Supported Minecraft/Fabric/Loader combinations.
- Suitable Loom, Gradle, and JDK requirements.
- Availability of entity registration, rendering, testing, and GameTest facilities.
- Potential API incompatibilities between the CC:Tweaked reference code and the target version.
- Any version facts that remain unverified.

Do not select versions solely from old tutorials or model memory. Provide evidence or source links for version-sensitive claims.

## Task 5 — Produce the proposed implementation plan

Recommend:

- The project directory layout.
- Which tools should be project-local versus system-installed.
- How to keep dependency versions reproducible.
- Whether Gradle caches should be redirected into a dedicated project-local directory.
- How to distinguish bootstrap downloads from subsequent offline builds.
- How to avoid modifying unrelated parts of the host system.
- The next smallest actionable task after this audit.

Do not create the project scaffold or install the toolchain yet.

## Required report format

Return the results in this order:

1. **Requirements understood** — concise summary of the actual specification.
2. **Host environment audit** — checked facts, missing information, and relevant findings.
3. **Self-contained options** — A/B/C comparison and recommendation.
4. **Version feasibility** — confirmed facts, uncertain facts, and sources.
5. **Proposed environment design** — concrete directory and dependency-management strategy.
6. **Risks and unresolved decisions** — clearly identify anything that cannot yet be verified.
7. **Next action** — one small, concrete task with explicit acceptance criteria.

Clearly label facts, assumptions, and recommendations.

Do not ask me a broad set of questions. Where the specification is ambiguous, explain the interpretations and recommend a default for review.

Stop after presenting this report. Make no project or system changes.