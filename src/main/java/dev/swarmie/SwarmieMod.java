package dev.swarmie;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
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

    public static final EntityType<SwarmieEntity> SWARMIE = registerSwarmie();

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

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                SwarmieCommands.register(dispatcher));
        SwarmieAdapter.initialize();

        LOGGER.info("Swarmie initialized; registered entity type swarmie:swarmie");
        LOGGER.info("Adapter contract loaded: v{}", ContractVersion.CURRENT);
    }
}
