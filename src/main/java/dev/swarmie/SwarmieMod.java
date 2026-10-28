package dev.swarmie;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.swarmie.adapter.SwarmieAdapter;
import dev.swarmie.command.SwarmieCommands;
import dev.swarmie.contract.ContractVersion;

/**
 * Common Swarmie entry point.
 *
 * <p>Registers the robot entity type and server-side debug commands. Client-only
 * registration lives in {@code dev.swarmie.client.SwarmieClient} and is never
 * referenced here.
 */
public final class SwarmieMod implements ModInitializer {
    public static final String MOD_ID = "swarmie";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final ResourceKey<EntityType<?>> SWARMIE_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, "swarmie"));

    private static final ResourceKey<Item> SWARMIE_SPAWN_EGG_KEY =
            ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, "swarmie_spawn_egg"));

    /**
     * Vanilla keeps its creative-tab keys private, so the spawn-egg tab key is
     * recreated here. Resource keys compare by value, so this resolves to the
     * same tab.
     */
    private static final ResourceKey<CreativeModeTab> SPAWN_EGGS_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("spawn_eggs"));

    public static final EntityType<SwarmieEntity> SWARMIE = registerSwarmie();

    /**
     * Spawn egg for a brand-new robot.
     *
     * <p>Using it creates a fresh entity with a new UUID and an empty nine-slot
     * inventory. The egg never stores, recalls or restores an existing robot;
     * {@code /summon} remains supported and behaves the same way.
     */
    public static final Item SWARMIE_SPAWN_EGG = registerSpawnEgg();

    private static EntityType<SwarmieEntity> registerSwarmie() {
        EntityType<SwarmieEntity> type = EntityType.Builder
                .of(SwarmieEntity::new, MobCategory.MISC)
                .sized(0.8F, 0.7F)
                .eyeHeight(0.45F)
                .clientTrackingRange(5)
                .updateInterval(3)
                .build(SWARMIE_KEY);

        Registry.register(BuiltInRegistries.ENTITY_TYPE, SWARMIE_KEY, type);
        return type;
    }

    private static Item registerSpawnEgg() {
        Item item = new SpawnEggItem(
                new Item.Properties()
                        .spawnEgg(SWARMIE)
                        .setId(SWARMIE_SPAWN_EGG_KEY));
        Registry.register(BuiltInRegistries.ITEM, SWARMIE_SPAWN_EGG_KEY, item);
        return item;
    }

    @Override
    public void onInitialize() {
        CreativeModeTabEvents.modifyOutputEvent(SPAWN_EGGS_TAB)
                .register(output -> output.accept(SWARMIE_SPAWN_EGG));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                SwarmieCommands.register(dispatcher));
        SwarmieAdapter.initialize();

        LOGGER.info("Swarmie initialized; registered entity type swarmie:swarmie");
        LOGGER.info("Swarmie spawn egg registered: swarmie:swarmie_spawn_egg");
        LOGGER.info("Adapter contract loaded: v{}", ContractVersion.CURRENT);
    }
}
