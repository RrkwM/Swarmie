# Gate 2 — Real Entity: implementation notes

> Scope: source implementation only. Gate 1 (Loom build environment) remains
> blocked, so **no runtime validation has been performed**.

## 1. Source layout

| File | Responsibility |
|---|---|
| `src/main/java/dev/swarmie/SwarmieMod.java` | Common entry point; registers `swarmie:swarmie` entity type |
| `src/main/java/dev/swarmie/SwarmieEntity.java` | Plain `Entity` body; no AI, movement, inventory, combat or custom sync |
| `src/client/java/dev/swarmie/client/SwarmieClient.java` | Client entry point; model layer + renderer registration |
| `src/client/java/dev/swarmie/client/SwarmieEntityRenderer.java` | 26.3 render-state renderer |
| `src/client/java/dev/swarmie/client/SwarmieRenderState.java` | Per-frame yaw/pitch state |
| `src/client/java/dev/swarmie/client/SwarmieEntityModel.java` | Temporary geometric drone model |
| `src/main/resources/assets/swarmie/textures/entity/swarmie.png` | 64×64 temporary drone texture |

## 2. Entity decisions

- **Plain `Entity`**, not `LivingEntity`, not `BlockEntity`. The body only needs
  position, rotation, tracking, persistence and native lifecycle.
- `MobCategory.MISC`: the robot is not a mob with AI or spawning rules.
- Collision box `0.8 × 0.7`, eye height `0.45`: compact drone.
- `clientTrackingRange(5)` chunks, `updateInterval(3)`: reasonable robot tracking.
- `setNoGravity(true)`: the body does not fall; **no movement controller or
  velocity logic** is implemented (Gate 3).
- No custom synced data, no custom save fields. Vanilla persists UUID, position,
  rotation, motion and standard entity state.
- `hurtServer` returns false: combat is out of scope at this gate.
- `/summon swarmie:swarmie` is the intended spawn path; the entity type is
  registered normally with `summon`/`serialize` defaults.

## 3. Verified 26.3 API evidence

All signatures below were inspected with `javap` against the local 26.3 client
jar (`tools/mc/loom-cache/26.3/minecraft-client.jar`) and, for server classes,
the nested `server-26.3.jar`; no version from 1.21/1.20 was assumed.

| API | Verified declaration |
|---|---|
| `Entity` | `Entity(EntityType<?>, Level)`; abstract `defineSynchedData(SynchedEntityData.Builder)`, `hurtServer(ServerLevel, DamageSource, float)`, `readAdditionalSaveData(ValueInput)`, `addAdditionalSaveData(ValueOutput)` |
| `Entity` state | `getYRot(float)`, `getXRot(float)`, `setNoGravity(boolean)`, `yRotO`, `xRotO` |
| Entity key | `net.minecraft.resources.Identifier` (not `ResourceLocation`) with `fromNamespaceAndPath` |
| Registration | `ResourceKey.create(Registries.ENTITY_TYPE, Identifier)`; `Registry.register(Registry<V>, ResourceKey<V>, T)` |
| Type builder | `EntityType.Builder.of(EntityType.EntityFactory, MobCategory)`, `.sized`, `.eyeHeight`, `.clientTrackingRange`, `.updateInterval`, `.build(ResourceKey<EntityType<?>>)` |
| Renderer base | `EntityRenderer<T extends Entity, S extends EntityRenderState>` with `createRenderState()`, `extractRenderState(T,S,float)`, `submit(S,PoseStack,SubmitNodeCollector,CameraRenderState)` |
| Model submission | `OrderedSubmitNodeCollector.submitModel(Model<? super S>, S, PoseStack, Identifier, int, int, int)` |
| Model | `EntityModel<S extends EntityRenderState>`, `ModelPart`, `LayerDefinition.create(MeshDefinition,int,int)` |
| Client registration | Fabric `ModelLayerRegistry.registerModelLayer(ModelLayerLocation, TexturedLayerDefinitionProvider)` and `EntityRendererRegistry.register(...)` |

Reference: Fabric's 26.3 example mod (`fabric-example-mod/tree/26.3`) uses
`splitEnvironmentSourceSets()`, `implementation` for Loader/API, and no
mappings dependency. `EntityRendererRegistry` is deprecated in 26.3 in favor of
`EntityRenderers.register` (made accessible by Fabric transitive access
wideners); the deprecated helper is used here because it is still functional
and does not require an AW-transformed classpath for static checks.

## 4. Static validation performed

- `javac` (project JDK 25) against the 26.3 client jar, extracted server jar and
  local Fabric dependency jars: **exit 0**.
- `javap` confirms `SwarmieEntity extends net.minecraft.world.entity.Entity`
  and `SwarmieEntityRenderer extends EntityRenderer<SwarmieEntity, SwarmieRenderState>`.
- `fabric.mod.json` and both lang files parse as JSON.
- Texture is 64×64 RGBA.
- Main source set contains no `net.minecraft.client` imports and no reference
  to the client entry point.
- No `LivingEntity`, `BlockEntity`, `SwarmieRuntime`, `InteractionActor`, or
  movement/physics code present.
- No leftover probe classes in `src/`.

## 5. Runtime validation procedure (not yet run)

Gate 1 must first be unblocked so a real Loom build and client/server launch work.

1. Build the mod and install it with Fabric Loader + Fabric API on Minecraft 26.3.
2. In a world, run `/summon swarmie:swarmie` at the player.
3. Confirm the entity exists with `/data get entity @e[type=swarmie:swarmie,limit=1]`
   and that its type is `swarmie:swarmie`.
4. Observe the client: a compact body with four rotor pods and a forward sensor,
   visibly not an invisible/default entity.
5. Save and quit, relaunch the world; confirm the same entity returns at the same
   position/UUID.
6. With a second client connected, confirm the entity is visible and tracked
   within 5 chunks; `updateInterval(3)` is a tracking setting, not a custom sync.
7. Remove it with `/kill @e[type=swarmie]`.

## 6. Known unverified items

- Loom build, resource processing, packaging and runtime loading — blocked by
  the Gate 1 ZipFS issue.
- Actual render transform/model proportions in-game (model uses standard 24 px
  feet convention with `scale(-1,-1,1)` and `translate(0,-1.5,0)`).
- Dedicated-server load with client classes absent (source sets separate, but
  no runtime test).
- `EntityRendererRegistry` deprecation migration.
