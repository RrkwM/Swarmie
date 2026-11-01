package dev.swarmie.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.swarmie.SwarmieEntity;
import dev.swarmie.SwarmieMod;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * 26.3 render-state based renderer for the white/cyan Swarmie shell.
 */
public class SwarmieEntityRenderer extends EntityRenderer<SwarmieEntity, SwarmieRenderState> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(SwarmieMod.MOD_ID, "swarmie"), "main");

    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(
            SwarmieMod.MOD_ID, "textures/entity/swarmie.png");

    private final SwarmieEntityModel model;

    public SwarmieEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new SwarmieEntityModel(context.bakeLayer(LAYER));
        this.shadowRadius = 0.2F;
        this.shadowStrength = 0.55F;
    }

    @Override
    public SwarmieRenderState createRenderState() {
        return new SwarmieRenderState();
    }

    @Override
    public void extractRenderState(SwarmieEntity entity, SwarmieRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.yRot = entity.getYRot(partialTick);
        state.xRot = entity.getXRot(partialTick);
    }

    @Override
    public void submit(
            SwarmieRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState camera) {
        poseStack.pushPose();
        // Idle hover: visual-only vertical bob; server position and hitbox do
        // not move. The four wing parts have their own idle animation in the
        // model setup.
        float bob = (float) Math.sin(state.ageInTicks * 0.075F) * 0.025F;
        poseStack.translate(0.0F, bob, 0.0F);
        poseStack.rotateDegrees(Axis.YP, 180.0F - state.yRot);
        // Four robots fit in one block (2x2), so the visual shell is about
        // 0.45 blocks wide; the entity hitbox is reduced to match.
        poseStack.scale(0.62F, 0.62F, 0.62F);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.translate(0.0F, -1.5F, 0.0F);

        collector.submitModel(
                this.model,
                state,
                poseStack,
                TEXTURE,
                state.lightCoords,
                OverlayTexture.NO_OVERLAY,
                state.outlineColor);

        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }
}
