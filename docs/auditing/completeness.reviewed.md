# Primitive completeness — reviewed catalogue

- format: swarmie-reviewed-catalogue/1
- generated: completeness.generated.json
- scope: Minecraft 26.3 serverbound play/common inputs, selected passive embodied mechanisms, clientbound observation surface, Fabric extension points
- note: This is a feasibility baseline, not a claim of completeness. Sources not listed here are reported by the validator as unreviewed.

## SEM-001

- title: Block destruction
- sources: SB.PLAY.PLAYER_ACTION, SB.PLAY.PLAYER_ACTION.ACTION.START_DESTROY_BLOCK, SB.PLAY.PLAYER_ACTION.ACTION.STOP_DESTROY_BLOCK, SB.PLAY.PLAYER_ACTION.ACTION.ABORT_DESTROY_BLOCK, SB.PLAY.PLAYER_ACTION.ACTION.CHANGE_DESTROY_DIRECTION
- disposition: MAPPED
- domain: blocks
- operation: destruction
- temporal: ACTION
- resources: selected tool/item in hand
- constraints: 6-block interaction range; loaded chunk; native ServerPlayerGameMode.handleBlockBreakAction; spawn protection and mayInteract guards; block hardness/tool rules
- rationale: Adapter MINE_BLOCK reaches native destroyBlock through the FakePlayer; start/stop/abort are the native lifecycle of one semantic primitive, not three primitives.
- fingerprint: SB.PLAY.PLAYER_ACTION=099381ff9425af6be7a4721d3f5cde22c019e83ed5b603b0a73e7b812955e370
- dispatch: SB.PLAY.PLAYER_ACTION=ServerPlayerGameMode.handleBlockBreakAction
- status: accepted
- questions: Is MINE_BLOCK's 6-block range identical to the vanilla guard path for every block type?

## SEM-002

- title: Use item on block / block activation
- sources: SB.PLAY.USE_ITEM_ON
- disposition: MAPPED
- domain: blocks, items
- operation: activation, item use
- temporal: ACTION
- resources: selected item stack and hand
- constraints: 6-block range; loaded chunk; mayInteract; spawn protection; build-limit guard; BlockHitResult direction and hit position; native ServerPlayerGameMode.useItemOn
- rationale: Adapter USE_ITEM_ON_BLOCK is this packet's native path; it covers placement and block activation but not menu-only interactions.
- fingerprint: SB.PLAY.USE_ITEM_ON=99c52b6428f217f90d8a17ea123e20932f56d1f198bc8a6d9ad06a8d826e31a7
- dispatch: SB.PLAY.USE_ITEM_ON=ServerPlayerGameMode.useItemOn
- status: accepted
- questions: Does the Adapter expose the full InteractionResult outcome or only success/failure?

## SEM-003

- title: Use item in air / hand use
- sources: SB.PLAY.USE_ITEM
- disposition: EXCLUDED
- domain: items
- operation: item use
- temporal: ACTION
- resources: selected item stack and hand
- constraints: held item must be enabled/non-empty; native ServerPlayerGameMode.useItem; rotation reconciliation
- rationale: The current Adapter exposes only USE_ITEM_ON_BLOCK; right-click-in-air item use is a distinct native operation and is not represented.
- fingerprint: SB.PLAY.USE_ITEM=121f20233b8a1830e611ebff425c603dbbedf2419de28fb1dd579887e21047b1
- dispatch: SB.PLAY.USE_ITEM=ServerPlayerGameMode.useItem
- status: proposed
- questions: Should air item use become a primitive, or is it always content-specific?

## SEM-004

- title: Entity attack
- sources: SB.PLAY.ATTACK
- disposition: EXCLUDED
- domain: entities
- operation: attack
- temporal: ACTION
- resources: main-hand item, attack cooldown
- constraints: attack range; world-border bounds; item-enabled and cannotAttackWithItem guards; native ServerPlayer.attack
- rationale: Attack is a generic embodied operation but the Adapter has no attack command. It is recorded as a concrete gap rather than silently mapped to interact.
- fingerprint: SB.PLAY.ATTACK=42cf5e4254b951cf52b6d0d40674a0825b8288b50033e9908ca73d3e824c2445
- dispatch: SB.PLAY.ATTACK=ServerPlayer.attack
- status: proposed
- questions: Is attack in the intended Adapter scope, or should it stay a content/weapon concern?

## SEM-005

- title: Entity interaction
- sources: SB.PLAY.INTERACT
- disposition: EXCLUDED
- domain: entities
- operation: interaction
- temporal: ACTION
- resources: selected item stack and hand
- constraints: entity interaction range; world-border bounds; secondary-action shift state; native ServerPlayer.interactOn
- rationale: Right-click entity interaction, including name-tag/mount/villager content, is not represented by the four current Adapter commands.
- fingerprint: SB.PLAY.INTERACT=455c5b6dd7b95e6ff8d169003e5f1c445424531f1ab1a030ef2ad2102aac9d81
- dispatch: SB.PLAY.INTERACT=ServerPlayer.interactOn
- status: proposed
- questions: Which entity interactions are generic primitives versus CONTENT?

## SEM-006

- title: Arm swing / punch animation input
- sources: SB.PLAY.PUNCH
- disposition: CONTENT
- domain: robot_self, presentation
- operation: swing feedback
- temporal: ACTION
- resources: selected item's swing animation
- constraints: no positional discriminator; triggers swing and resets attack strength
- rationale: This packet changes the arm-swing state/feedback; it is not a world operation and has no Adapter command.
- fingerprint: SB.PLAY.PUNCH=993f72190ace88c7940e1a4516486791a9374d870368b6e85eef378f53ebc060
- dispatch: SB.PLAY.PUNCH=ServerPlayer.swing
- status: accepted
- questions: none

## SEM-007

- title: Selected hotbar slot
- sources: SB.PLAY.SET_CARRIED_ITEM
- disposition: EXCLUDED
- domain: inventory, robot_self
- operation: selection
- temporal: SETPOINT
- resources: none
- constraints: slot must be within Inventory.getSelectionSize(); stops using item on change
- rationale: The robot inventory tracks a selected slot for MINE/USE, but there is no public Adapter operation to change it, so the packet itself is not mapped.
- fingerprint: SB.PLAY.SET_CARRIED_ITEM=07a62ab50f091133f4c6d3e94a6cc1203f14a282fe2b583ae2dbac6fe9f246ee
- dispatch: SB.PLAY.SET_CARRIED_ITEM=Inventory.setSelectedSlot
- status: proposed
- questions: Should slot selection be a public primitive or an implementation detail of content commands?

## SEM-008

- title: Inventory menu operations
- sources: SB.PLAY.CONTAINER_CLICK, SB.PLAY.CONTAINER_BUTTON_CLICK, SB.PLAY.CONTAINER_CLOSE, SB.PLAY.CONTAINER_SLOT_STATE_CHANGED, SB.PLAY.SET_CREATIVE_MODE_SLOT
- disposition: EXCLUDED
- domain: inventory, menus
- operation: transfer, menu action
- temporal: ACTION
- resources: open container/menu and carried stack
- constraints: menu still valid; slot index valid; state id matches; creative-only for SET_CREATIVE_MODE_SLOT; native AbstractContainerMenu.clicked
- rationale: The Adapter owns a 9-slot robot inventory, not a player menu. GUI container support is a documented limitation, so these packets are deliberately out of scope.
- fingerprint: SB.PLAY.CONTAINER_CLICK=5e08fb9dfc41f450a0cd0b8ab7593ceea42eb763e3baa81eaf042666f425c639
- dispatch: SB.PLAY.CONTAINER_CLICK=AbstractContainerMenu.clicked
- status: accepted
- questions: Does a future narrow container primitive require the full menu state machine?

## SEM-009

- title: Player movement, orientation and input state
- sources: SB.PLAY.MOVE_PLAYER, SB.PLAY.MOVE_VEHICLE, SB.PLAY.PLAYER_INPUT, SB.PLAY.PLAYER_COMMAND, SB.PLAY.PADDLE_BOAT
- disposition: MAPPED
- domain: robot_self, world_environment
- operation: motion control, orientation
- temporal: SETPOINT
- resources: none
- constraints: server position correction/teleport acceptance; invalid value disconnect; native handlePlayerPositionChange; vehicle and boat variants are separate embodied contexts
- rationale: Adapter TIMED_FLIGHT/STOP establish a velocity setpoint; these packets describe the ordinary player movement/input surface, not additional public commands.
- fingerprint: SB.PLAY.MOVE_PLAYER=a0be7661ff0145332baafe0d7ce8e24672260892bd544261bdc369b11726c686
- dispatch: SB.PLAY.MOVE_PLAYER=handlePlayerPositionChange
- status: accepted
- questions: Which parts of PLAYER_INPUT/PLAYER_COMMAND are genuinely needed for a hovering robot?

## SEM-010

- title: Sign editing
- sources: SB.PLAY.SIGN_UPDATE
- disposition: CONTENT
- domain: blocks, text
- operation: write sign text
- temporal: ACTION
- resources: sign block entity context
- constraints: block-specific; text filtering is asynchronous
- rationale: Sign text is a concrete block-entity operation, not a generic primitive.
- fingerprint: SB.PLAY.SIGN_UPDATE=8e96ee72473e436efd8d6f6e9398c9eb3e7bacb9f1c35c1974ecad4627f04bd7
- status: accepted
- questions: none

## SEM-011

- title: Book editing
- sources: SB.PLAY.EDIT_BOOK
- disposition: CONTENT
- domain: items, text
- operation: write book
- temporal: ACTION
- resources: hotbar book item
- constraints: hotbar slot; page limits; asynchronous text filtering
- rationale: Book content editing is item-specific content, not a generic robot primitive.
- fingerprint: SB.PLAY.EDIT_BOOK=893ed4f37dca4df6bcc717275ce5e9ceac3e13f7761bf88089664b7bc3c9251a
- status: accepted
- questions: none

## SEM-012

- title: Rename, trade selection and recipe placement
- sources: SB.PLAY.RENAME_ITEM, SB.PLAY.SELECT_TRADE, SB.PLAY.PLACE_RECIPE
- disposition: CONTENT
- domain: menus, items
- operation: menu-specific actions
- temporal: ACTION
- resources: open anvil/merchant/crafting menu
- constraints: menu still valid; recipe known; placement possible
- rationale: These are menu-content operations; the Adapter has no GUI-container primitive.
- fingerprint: SB.PLAY.RENAME_ITEM=f4fa5d72de093e6773e0e8e327ff27d1a4f7725578c8d59eec483df68593ebbd
- status: accepted
- questions: none

## SEM-013

- title: Commands and suggestions
- sources: SB.PLAY.CHAT_COMMAND, SB.PLAY.CHAT_COMMAND_SIGNED, SB.PLAY.COMMAND_SUGGESTION
- disposition: PRIVILEGED
- domain: world_environment, robot_self
- operation: administration/commands
- temporal: ACTION
- resources: permission level
- constraints: command permission; signed-command session; no embodied reach
- rationale: Commands are a management surface, not ordinary embodied behavior; the Adapter must not become a command proxy.
- fingerprint: SB.PLAY.CHAT_COMMAND=0e86b316c52607bb9a9302764547b231925dd835a53cf89bf5b19b6aadc85b7d
- status: accepted
- questions: none

## SEM-014

- title: Chat and chat session state
- sources: SB.PLAY.CHAT, SB.PLAY.CHAT_ACK, SB.PLAY.CHAT_SESSION_UPDATE
- disposition: PRIVILEGED
- domain: social, robot_self
- operation: chat
- temporal: ACTION
- resources: chat session/signature state
- constraints: chat reporting/signature rules; social screen
- rationale: Chat is a player/social capability, not a world primitive.
- fingerprint: SB.PLAY.CHAT=d4aa766087f612a72ad595cfd710cfc35a93794a3868ab525a1965f13a1be562
- status: accepted
- questions: none

## SEM-015

- title: Admin and session state changes
- sources: SB.PLAY.CHANGE_DIFFICULTY, SB.PLAY.CHANGE_GAME_MODE, SB.PLAY.LOCK_DIFFICULTY, SB.PLAY.SET_GAME_RULE, SB.PLAY.PLAYER_ABILITIES, SB.PLAY.CLIENT_COMMAND
- disposition: PRIVILEGED
- domain: world_environment, robot_self
- operation: administration
- temporal: ACTION
- resources: permission level / server operator
- constraints: permission checks; difficulty lock; respawn/stats requests
- rationale: These change server/session settings rather than embodied robot behavior.
- fingerprint: SB.PLAY.CHANGE_GAME_MODE=6e00207bfe7ddd43575b80f3c96b3a3de1e55d00cb8bf682bcf1c0497ba4ad40
- status: accepted
- questions: none

## SEM-016

- title: NBT/debug tag queries
- sources: SB.PLAY.BLOCK_ENTITY_TAG_QUERY, SB.PLAY.ENTITY_TAG_QUERY, SB.PLAY.DEBUG_SUBSCRIPTION_REQUEST
- disposition: PRIVILEGED
- domain: blocks, entities, debug
- operation: diagnostic query
- temporal: QUERY
- resources: permission level 2
- constraints: loaded chunk; query transaction id
- rationale: Raw tag queries are operator diagnostics, not the robot's typed observation interface.
- fingerprint: SB.PLAY.BLOCK_ENTITY_TAG_QUERY=c0f7d5a9eab24928e7cf8cded74dea3e7077e520de92df3e765a816cc411c06a
- status: accepted
- questions: none

## SEM-017

- title: Common connection protocol
- sources: SB.COMMON.KEEP_ALIVE, SB.COMMON.PONG, SB.COMMON.CUSTOM_PAYLOAD, SB.COMMON.RESOURCE_PACK, SB.COMMON.CLIENT_INFORMATION
- disposition: PROTOCOL_MECHANISM
- domain: connection, client settings
- operation: keepalive, resource pack, payload transport
- temporal: n/a
- resources: none
- constraints: common listener; custom payload dispatch is not visible in the handler body
- rationale: These are connection/transport mechanisms. Client information is configuration, not a world operation.
- fingerprint: SB.COMMON.KEEP_ALIVE=2d0ddcbfb76c0e660a647f182e529becde7bb5774fb4967d7a0447117379d967
- status: accepted
- questions: Should CLIENT_INFORMATION be PRIVILEGED rather than PROTOCOL_MECHANISM?

## SEM-018

- title: Command/jigsaw/structure/test/beacon block editors
- sources: SB.PLAY.SET_COMMAND_BLOCK, SB.PLAY.SET_COMMAND_MINECART, SB.PLAY.SET_BEACON, SB.PLAY.SET_JIGSAW_BLOCK, SB.PLAY.JIGSAW_GENERATE, SB.PLAY.SET_STRUCTURE_BLOCK, SB.PLAY.SET_TEST_BLOCK, SB.PLAY.TEST_INSTANCE_BLOCK_ACTION
- disposition: CONTENT
- domain: blocks, world_generation
- operation: editor-specific configuration
- temporal: ACTION
- resources: block entity / editor permission
- constraints: block type and mode; structure bounds; test instance context
- rationale: These are concrete block/editor implementations dispatched through the generic use-on-block path, not primitive operations.
- fingerprint: SB.PLAY.SET_STRUCTURE_BLOCK=0ef736b37225fd28e610f16e2cfbd9cae137c3f2580682b033adc817b56781b3
- status: accepted
- questions: none

## SEM-019

- title: Clientbound block observations
- sources: CB.PLAY.BLOCK_UPDATE, CB.PLAY.SECTION_BLOCKS_UPDATE, CB.PLAY.BLOCK_CHANGED_ACK
- disposition: EXCLUDED
- domain: blocks
- operation: observation
- temporal: QUERY
- resources: client connection
- constraints: prediction/ack correlation; server-authoritative block state
- rationale: Available to an ordinary client, but the robot controller's observation path is the server-side WorldQuery, not client packets. This is a review baseline, not the public query API.
- fingerprint: CB.PLAY.BLOCK_UPDATE=7ec49960d33707d42122d4fea6510031bc2fc1bbcce4f6c6aa1198ce59a89f73
- status: proposed
- questions: Should any clientbound packet ever feed robot observation, or only server-side queries?

## SEM-020

- title: Clientbound entity observations
- sources: CB.PLAY.ADD_ENTITY, CB.PLAY.REMOVE_ENTITIES, CB.PLAY.MOVE_ENTITY, CB.PLAY.ENTITY_POSITION_SYNC, CB.PLAY.SET_ENTITY_DATA, CB.PLAY.TELEPORT_ENTITY
- disposition: EXCLUDED
- domain: entities
- operation: observation
- temporal: QUERY
- resources: client connection
- constraints: interpolation/tracking state; entity data may be partial
- rationale: Same embodied-principle decision as block observations: client packets are presentation/delta streams, not a guaranteed observation contract.
- fingerprint: CB.PLAY.ADD_ENTITY=8f73fc92cb96d539ca4f9a2cce370d82aa022ba3a2a6743a17a7e222af893899
- status: proposed
- questions: none

## SEM-021

- title: Clientbound inventory observations
- sources: CB.PLAY.CONTAINER_SET_CONTENT, CB.PLAY.CONTAINER_SET_SLOT, CB.PLAY.SET_PLAYER_INVENTORY, CB.PLAY.SET_CURSOR_ITEM, CB.PLAY.SET_HELD_SLOT
- disposition: EXCLUDED
- domain: inventory
- operation: observation
- temporal: QUERY
- resources: client connection
- constraints: remote state ids; prediction reconciliation
- rationale: The robot inventory is server-authoritative; client container packets are not a controller observation API.
- fingerprint: CB.PLAY.CONTAINER_SET_SLOT=2ae231b956f65e49115eabb3efa224f940a5eb2d1c1e90da8d9e2e62a01c8387
- status: proposed
- questions: none

## SEM-022

- title: Clientbound world/chunk observations
- sources: CB.PLAY.SET_TIME, CB.PLAY.GAME_EVENT, CB.PLAY.LEVEL_CHUNK_WITH_LIGHT, CB.PLAY.FORGET_LEVEL_CHUNK, CB.PLAY.LIGHT_UPDATE
- disposition: EXCLUDED
- domain: world_environment
- operation: observation
- temporal: QUERY
- resources: client connection
- constraints: chunk tracking/light state; server-side query avoids delivery gaps
- rationale: Chunk streaming is a client-loading concern; the robot's server-side query does not depend on these packets.
- fingerprint: CB.PLAY.SET_TIME=0145f88cbd7f24ceeaee18de7ab3c2fdf627ae867a965eda8e27dd3ac9ef1e27
- status: proposed
- questions: none

## SEM-023

- title: Presentation and UI feedback
- sources: CB.PLAY.SOUND, CB.PLAY.LEVEL_PARTICLES, CB.PLAY.ANIMATE, CB.PLAY.OPEN_SCREEN, CB.PLAY.SET_TITLE_TEXT, CB.PLAY.BOSS_EVENT
- disposition: CONTENT
- domain: presentation, UI
- operation: feedback
- temporal: n/a
- resources: client resources
- constraints: no authoritative state change; screen-specific
- rationale: Sounds, particles, animations and screens are presentation, not world-information or robot primitives.
- fingerprint: CB.PLAY.SOUND=ca768b9c7fc7ae6b56bbefa9d1d3939bb2df99a853b67c5f3c03a6595a293104
- status: accepted
- questions: none

## SEM-024

- title: Passive motion, collision and inside-block scanning
- sources: PAS.Entity.move#acc048f8, PAS.Entity.collide#fd2f88de, PAS.Entity.checkInsideBlocks#2a2ec5e2
- disposition: MAPPED
- domain: robot_self, blocks, entities
- operation: embodied motion/collision
- temporal: SETPOINT
- resources: bounding box and level collision state
- constraints: native Entity.move/collide; server collision; block/entity collision; step-up; world border
- rationale: The robot body relies on native movement/collision; this is the embodied substrate of flight, not a separate command.
- fingerprint: PAS.Entity.move#acc048f8=789337940da3b918e4abbfad50b9fe1861297f4378a2ba7024389864b7575b9e
- dispatch: PAS.Entity.checkInsideBlocks#2a2ec5e2=BlockGetter.forEachBlockIntersectedBetween
- status: accepted
- questions: The entityInside dispatch happens through a lambda/invokedynamic; the extractor records the lambda count but not its target.

## SEM-025

- title: Block-driven entity effects
- sources: PAS.BlockBehaviour$BlockStateBase.entityInside#cb24ffce, PAS.BasePressurePlateBlock.entityInside#c245813b, PAS.TripWireBlock.entityInside#c245813b
- disposition: CONTENT
- domain: blocks, entities
- operation: environment trigger
- temporal: ACTION
- resources: block state
- constraints: block-specific implementation dispatched from BlockState.entityInside
- rationale: Pressure plates, tripwire and other blocks are concrete content implementations; the generic dispatch is represented by the block state hook, not one primitive per block.
- fingerprint: PAS.BlockBehaviour$BlockStateBase.entityInside#cb24ffce=91c0e82020e62b325f7b3e1be917d7d354e334961e6e22808c9adf291bdc19cb
- status: accepted
- questions: none

## SEM-026

- title: Item pickup
- sources: PAS.ItemEntity.playerTouch#97e71872, PAS.LivingEntity.take#a421cd36, PAS.ServerPlayer.take#a421cd36
- disposition: EXCLUDED
- domain: items, entities
- operation: pickup
- temporal: ACTION
- resources: inventory space
- constraints: item pickup delay/ownership; inventory add; player-touch path
- rationale: Swarmie does not collect drops; mining drops stay in the world. There is no pickup primitive.
- fingerprint: PAS.ItemEntity.playerTouch#97e71872=03c8e8e10df7cb0573044e2422d0c2667f9150f94b4eb810ea1fd89dcc73c902
- status: proposed
- questions: Is pickup in scope for the robot platform, or intentionally excluded?

## SEM-027

- title: Damage to the embodied entity
- sources: PAS.Entity.hurtServer#b0771c06, PAS.LivingEntity.hurtServer#b0771c06, PAS.Player.hurtServer#b0771c06, PAS.ServerPlayer.hurtServer#b0771c06
- disposition: EXCLUDED
- domain: robot_self, entities
- operation: damage
- temporal: ACTION
- resources: damage source
- constraints: LivingEntity-only path; SwarmieEntity is a plain Entity and does not use LivingEntity damage
- rationale: The robot is deliberately not a LivingEntity; damage/health is not part of the current model.
- fingerprint: PAS.LivingEntity.hurtServer#b0771c06=9d2512c6adcdcb21668ba7014d56b1a47cfa3b2635f0f0688dacfdc672f39fd5
- status: accepted
- questions: Should robots ever be damageable, or remain non-living automation?

## SEM-028

- title: Portal transition
- sources: PAS.Entity.handlePortal#da8c82d5, PAS.Entity.setAsInsidePortal#0b0ed4d1
- disposition: EXCLUDED
- domain: world_environment, robot_self
- operation: dimension transition
- temporal: ACTION
- resources: portal block context
- constraints: portal cooldown; destination search; vanilla TeleportTransition
- rationale: Dimension changes are observed as lifecycle events, but there is no portal primitive in the Adapter.
- fingerprint: PAS.Entity.handlePortal#da8c82d5=c55b5be1e84518f30a583029b461bc9eea756e5e4185b086ae99a38fae71f84b
- status: accepted
- questions: none

## SEM-029

- title: Fabric custom payload type declaration
- sources: EXT.FABRIC.PAYLOAD_TYPE_REGISTRY.register#e6bc8973, EXT.FABRIC.PAYLOAD_TYPE_REGISTRY.registerLarge#fd03039d, EXT.FABRIC.PAYLOAD_TYPE_REGISTRY.serverboundPlay#58ec5638
- disposition: PROTOCOL_MECHANISM
- domain: connection
- operation: payload type declaration
- temporal: n/a
- resources: none
- constraints: global registry; no public getter enumerates declared types
- rationale: Payload types are transport declarations. The API exposes registration but not a global enumeration of declared types.
- fingerprint: EXT.FABRIC.PAYLOAD_TYPE_REGISTRY.register#e6bc8973=7f4c902c5f1ed34d208a71c3396e469f75ed49451959393d21307c8435b2e49f
- status: accepted
- questions: Can a runtime probe enumerate declared types through the registry implementation, or only receivers?

## SEM-030

- title: Fabric server payload receivers
- sources: EXT.FABRIC.SERVER_PLAY_NETWORKING.registerGlobalReceiver#603bb808, EXT.FABRIC.SERVER_PLAY_NETWORKING.getGlobalReceivers#9aa7af2a, EXT.FABRIC.SERVER_PLAY_NETWORKING.registerReceiver#84cef19a, EXT.FABRIC.SERVER_PLAY_NETWORKING.getReceived#66dd1e17
- disposition: PROTOCOL_MECHANISM
- domain: connection, mods
- operation: payload dispatch
- temporal: n/a
- resources: server/connection
- constraints: global receivers are enumerable by identifier; per-connection receivers are enumerable only for a live connection
- rationale: Receiver registration is a transport mechanism, not a world primitive. It is content-extensible and cannot be fully enumerated offline.
- fingerprint: EXT.FABRIC.SERVER_PLAY_NETWORKING.registerGlobalReceiver#603bb808=3da9c6825229c25bf0621a06f55463cf8f7175afd4fd8aa8f9d77be5164b8d60
- status: accepted
- questions: none

## SEM-031

- title: Fabric item storage lookup and transfer
- sources: EXT.FABRIC.ITEM_STORAGE.SIDED, EXT.FABRIC.BLOCK_API_LOOKUP.find#d3f47900, EXT.FABRIC.STORAGE.insert#10245b9a, EXT.FABRIC.STORAGE.extract#10245b9a
- disposition: EXCLUDED
- domain: inventory, blocks
- operation: transfer
- temporal: ACTION
- resources: world position, side, loaded block entity
- constraints: lookup is position/side dependent; no global storage registry; transactional insert/extract
- rationale: The Adapter has no transfer primitive. ItemStorage is a generic capability shape; concrete storages are content and require a live world probe.
- fingerprint: EXT.FABRIC.ITEM_STORAGE.SIDED=1cfa9e4530b0061cb186e0a5108458d0dd2492b070315f44cd07b4cea7dc2c58
- status: proposed
- questions: Is transfer a future primitive, or left to content-specific robots?

## SEM-032

- title: Clientbound batching/configuration protocol
- sources: CB.PLAY.BUNDLE, CB.PLAY.CHUNK_BATCH_START, CB.PLAY.CHUNK_BATCH_FINISHED, CB.PLAY.START_CONFIGURATION
- disposition: PROTOCOL_MECHANISM
- domain: connection
- operation: batching/configuration
- temporal: n/a
- resources: client connection
- constraints: packet ordering; configuration phase
- rationale: These are delivery/connection mechanisms, not observations or world operations.
- fingerprint: CB.PLAY.BUNDLE=150f307274eb13b56874cf20efd2ed14ea67fc2c46b8b8380ae56889b77a8af0
- status: accepted
- questions: none
