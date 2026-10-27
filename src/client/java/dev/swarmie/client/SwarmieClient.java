package dev.swarmie.client;

import dev.swarmie.SwarmieMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;

/**
 * Client-only entry point. Never loaded by the dedicated server.
 */
public final class SwarmieClient implements ClientModInitializer {
    @Override
    @SuppressWarnings("deprecation") // 26.3 prefers EntityRenderers.register via Fabric transitive AW
    public void onInitializeClient() {
        ModelLayerRegistry.registerModelLayer(
                SwarmieEntityRenderer.LAYER,
                SwarmieEntityModel::createBodyLayer);

        EntityRendererRegistry.register(
                SwarmieMod.SWARMIE,
                SwarmieEntityRenderer::new);
    }
}
