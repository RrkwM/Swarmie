English | [简体中文](Installation-zh-CN)

# Installation

## Requirements

| Component | Version |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.165.0+26.3 |
| Java | 25 |

## Client

1. Install Fabric Loader for Minecraft 26.3.
2. Download the release JAR:
   https://github.com/RrkwM/Swarmie/releases/tag/v0.1.0
3. Put `swarmie-0.1.0.jar` in the game `mods/` folder.
4. Put the matching Fabric API JAR in `mods/`.
5. Start the game with Java 25.

Verify the download:

```powershell
Get-FileHash .\swarmie-0.1.0.jar -Algorithm SHA256
```

The release page publishes the expected SHA-256.

## First robot

0.1.0 has no crafting/item/spawn-egg acquisition. As an OP:

```mcfunction
/summon swarmie:swarmie ~ ~ ~
/swarmie status @e[type=swarmie:swarmie,limit=1]
```

## Dedicated server

A dedicated-server smoke test has not been completed. Use the client preview
unless you are prepared to test server startup yourself. The mod declares
`environment: "*"`, but that is not a claim of dedicated-server verification.

