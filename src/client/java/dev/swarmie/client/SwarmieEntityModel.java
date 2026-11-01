package dev.swarmie.client;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * White spherical robot with a dark cyan-rimmed face screen, two large
 * ear-fins and small side fins. Boxes are used only because the entity model
 * format is cuboid-based; the silhouette and colour blocking follow the
 * reference art.
 */
public class SwarmieEntityModel extends EntityModel<SwarmieRenderState> {
    private final ModelPart earLeft;
    private final ModelPart earRight;
    private final ModelPart finLeft;
    private final ModelPart finRight;

    public SwarmieEntityModel(ModelPart root) {
        super(root);
        this.earLeft = root.getChild("ear_left");
        this.earRight = root.getChild("ear_right");
        this.finLeft = root.getChild("fin_left");
        this.finRight = root.getChild("fin_right");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        // Voxel sphere, centred at y=20. It is deliberately made from small
        // boxes so the silhouette stays round at the new 0.45-block scale.
        CubeListBuilder bodyShell = CubeListBuilder.create().texOffs(0, 0);
        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -4; z <= 4; z++) {
                    if (x * x + y * y + z * z <= 19) {
                        bodyShell.addBox(x, 20 + y, z, 1, 1, 1);
                    }
                }
            }
        }
        root.addOrReplaceChild("body", bodyShell, PartPose.ZERO);

        // Dark face panel, slightly proud of the shell, with a cyan rim.
        root.addOrReplaceChild("face",
                CubeListBuilder.create().texOffs(64, 0)
                        .addBox(-3.0F, 18.0F, -4.9F, 6.0F, 4.5F, 0.7F)
                        .addBox(-2.0F, 17.3F, -4.9F, 4.0F, 0.7F, 0.7F)
                        .addBox(-2.0F, 22.5F, -4.9F, 4.0F, 0.7F, 0.7F)
                        .addBox(-3.6F, 18.7F, -4.9F, 0.6F, 3.2F, 0.7F)
                        .addBox(3.0F, 18.7F, -4.9F, 0.6F, 3.2F, 0.7F),
                PartPose.ZERO);
        root.addOrReplaceChild("face_rim",
                CubeListBuilder.create().texOffs(128, 0)
                        .addBox(-3.4F, 17.8F, -5.0F, 6.8F, 0.35F, 0.6F)
                        .addBox(-3.4F, 22.2F, -5.0F, 6.8F, 0.35F, 0.6F)
                        .addBox(-3.9F, 18.15F, -5.0F, 0.35F, 4.05F, 0.6F)
                        .addBox(3.55F, 18.15F, -5.0F, 0.35F, 4.05F, 0.6F),
                PartPose.ZERO);

        // Two pale screen highlights/eyes.
        root.addOrReplaceChild("eyes",
                CubeListBuilder.create().texOffs(0, 64)
                        .addBox(-2.2F, 19.0F, -5.12F, 1.0F, 2.4F, 0.12F)
                        .addBox(1.2F, 19.0F, -5.12F, 1.0F, 2.4F, 0.12F)
                        .addBox(-0.7F, 21.0F, -5.12F, 1.4F, 0.25F, 0.12F),
                PartPose.ZERO);
        root.addOrReplaceChild("screen_glint",
                CubeListBuilder.create().texOffs(192, 0)
                        .addBox(-2.8F, 20.0F, -5.14F, 0.45F, 1.4F, 0.1F)
                        .addBox(2.0F, 19.3F, -5.14F, 0.35F, 0.9F, 0.1F),
                PartPose.ZERO);

        // Large swept-back ear fins, built as shallow voxel ellipses so they
        // read as rounded plates rather than rectangular rods.
        root.addOrReplaceChild("ear_left", earShell(),
                PartPose.offsetAndRotation(-2.8F, 15.5F, 0.0F,
                        0.12F, 0.0F, -0.34F));
        root.addOrReplaceChild("ear_right", earShell(),
                PartPose.offsetAndRotation(2.8F, 15.5F, 0.0F,
                        0.12F, 0.0F, 0.34F));

        // Small cyan-tipped side fins.
        root.addOrReplaceChild("fin_left",
                CubeListBuilder.create().texOffs(64, 64)
                        .addBox(-2.0F, -1.0F, -1.0F, 3.0F, 2.0F, 1.5F)
                        .texOffs(128, 0)
                        .addBox(-3.0F, -0.6F, -0.55F, 1.4F, 1.2F, 0.8F),
                PartPose.offsetAndRotation(-4.2F, 20.0F, 0.0F,
                        0.0F, 0.0F, -0.18F));
        root.addOrReplaceChild("fin_right",
                CubeListBuilder.create().texOffs(64, 64)
                        .addBox(-1.0F, -1.0F, -1.0F, 3.0F, 2.0F, 1.5F)
                        .texOffs(128, 0)
                        .addBox(1.6F, -0.6F, -0.55F, 1.4F, 1.2F, 0.8F),
                PartPose.offsetAndRotation(4.2F, 20.0F, 0.0F,
                        0.0F, 0.0F, 0.18F));

        // Cyan collar at the underside, visible in the reference silhouette.
        root.addOrReplaceChild("collar",
                CubeListBuilder.create().texOffs(128, 0)
                        .addBox(-1.2F, 24.3F, -1.0F, 2.4F, 1.0F, 2.0F),
                PartPose.ZERO);

        return LayerDefinition.create(mesh, 256, 256);
    }

    private static CubeListBuilder earShell() {
        CubeListBuilder shell = CubeListBuilder.create().texOffs(0, 0);
        for (int x = -1; x <= 1; x++) {
            for (int y = -6; y <= -1; y++) {
                double nx = x / 1.8;
                double ny = (y + 3.5) / 2.7;
                if (nx * nx + ny * ny <= 1.2) {
                    for (int z = -1; z <= 0; z++) {
                        shell.addBox(x, y, z, 1, 1, 1);
                    }
                }
            }
        }
        shell.texOffs(128, 0)
                .addBox(-0.6F, -5.2F, -1.12F, 1.2F, 4.2F, 0.18F);
        return shell;
    }

    @Override
    public void setupAnim(SwarmieRenderState state) {
        this.root.getAllParts().forEach(ModelPart::resetPose);
        float t = state.ageInTicks;

        // Four independent wing parts. The two upper ear-fins breathe slowly;
        // the lower side fins have a separate, slightly faster idle rhythm.
        float earWave = (float) Math.sin(t * 0.075F);
        float earLift = (float) Math.cos(t * 0.052F);
        this.earLeft.zRot = -0.34F + earWave * 0.075F;
        this.earRight.zRot = 0.34F - earWave * 0.075F;
        this.earLeft.xRot = 0.12F + earLift * 0.035F;
        this.earRight.xRot = 0.12F - earLift * 0.035F;

        float finWave = (float) Math.sin(t * 0.11F + 1.4F);
        float finSweep = (float) Math.cos(t * 0.085F);
        this.finLeft.zRot = -0.18F + finWave * 0.11F;
        this.finRight.zRot = 0.18F - finWave * 0.11F;
        this.finLeft.yRot = finSweep * 0.08F;
        this.finRight.yRot = -finSweep * 0.08F;
    }
}
