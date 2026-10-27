package dev.swarmie.client;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/**
 * Per-frame render state for a Swarmie. Only values copied from the entity are
 * stored here; no gameplay state and no custom networking.
 */
public class SwarmieRenderState extends EntityRenderState {
    public float yRot;
    public float xRot;
}
