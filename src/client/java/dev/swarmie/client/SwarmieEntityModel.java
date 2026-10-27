package dev.swarmie.client;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * Temporary Gate 2 drone model: a compact central body with four rotor pods
 * and a small forward sensor. Deliberately not final art.
 */
public class SwarmieEntityModel extends EntityModel<SwarmieRenderState> {
    private final ModelPart rotorLeft;
    private final ModelPart rotorRight;
    private final ModelPart rotorFront;
    private final ModelPart rotorBack;
    private final ModelPart sensor;

    public SwarmieEntityModel(ModelPart root) {
        super(root);
        this.rotorLeft = root.getChild("rotor_left");
        this.rotorRight = root.getChild("rotor_right");
        this.rotorFront = root.getChild("rotor_front");
        this.rotorBack = root.getChild("rotor_back");
        this.sensor = root.getChild("sensor");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        root.addOrReplaceChild("body",
                CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-3.0F, 18.0F, -3.0F, 6.0F, 6.0F, 6.0F),
                PartPose.ZERO);

        root.addOrReplaceChild("sensor",
                CubeListBuilder.create().texOffs(32, 0)
                        .addBox(-1.0F, 15.0F, -1.0F, 2.0F, 3.0F, 2.0F),
                PartPose.ZERO);

        root.addOrReplaceChild("arm_left",
                CubeListBuilder.create().texOffs(32, 40)
                        .addBox(-6.0F, 19.5F, -1.0F, 2.0F, 1.0F, 2.0F),
                PartPose.ZERO);
        root.addOrReplaceChild("arm_right",
                CubeListBuilder.create().texOffs(32, 40)
                        .addBox(4.0F, 19.5F, -1.0F, 2.0F, 1.0F, 2.0F),
                PartPose.ZERO);
        root.addOrReplaceChild("arm_front",
                CubeListBuilder.create().texOffs(32, 40)
                        .addBox(-1.0F, 19.5F, -6.0F, 2.0F, 1.0F, 2.0F),
                PartPose.ZERO);
        root.addOrReplaceChild("arm_back",
                CubeListBuilder.create().texOffs(32, 40)
                        .addBox(-1.0F, 19.5F, 4.0F, 2.0F, 1.0F, 2.0F),
                PartPose.ZERO);

        root.addOrReplaceChild("rotor_left",
                CubeListBuilder.create().texOffs(32, 16)
                        .addBox(-1.5F, -0.5F, -1.5F, 3.0F, 1.0F, 3.0F),
                PartPose.offset(-4.5F, 20.0F, 0.0F));
        root.addOrReplaceChild("rotor_right",
                CubeListBuilder.create().texOffs(32, 16)
                        .addBox(-1.5F, -0.5F, -1.5F, 3.0F, 1.0F, 3.0F),
                PartPose.offset(4.5F, 20.0F, 0.0F));
        root.addOrReplaceChild("rotor_front",
                CubeListBuilder.create().texOffs(32, 16)
                        .addBox(-1.5F, -0.5F, -1.5F, 3.0F, 1.0F, 3.0F),
                PartPose.offset(0.0F, 20.0F, -4.5F));
        root.addOrReplaceChild("rotor_back",
                CubeListBuilder.create().texOffs(32, 16)
                        .addBox(-1.5F, -0.5F, -1.5F, 3.0F, 1.0F, 3.0F),
                PartPose.offset(0.0F, 20.0F, 4.5F));

        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(SwarmieRenderState state) {
        this.root.getAllParts().forEach(ModelPart::resetPose);

        float spin = state.ageInTicks * 30.0F;
        this.rotorLeft.yRot = spin;
        this.rotorRight.yRot = -spin;
        this.rotorFront.yRot = spin;
        this.rotorBack.yRot = -spin;

        this.sensor.xRot = (float) Math.sin(state.ageInTicks * 0.15F) * 0.08F;
    }
}
