package uk.nashhost.zyncraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.EndermanModel;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import uk.nashhost.zyncraft.network.SyncCarriedBlocksPacket;

import java.util.List;

/**
 * Draws every block in an Enderman's carried cuboid above its head, instead of
 * just the one block vanilla's own CarriedBlockLayer shows (that layer only ever
 * reads EnderMan.getCarriedBlock(), a single-BlockState field). This is a
 * separate, additional layer registered alongside vanilla's, not a replacement
 * for it.
 * <p>
 * Reads from ClientCarriedBlocks, which only exists because of
 * SyncCarriedBlocksPacket - there's no direct link to the server's
 * CarriedBlockArea from here.
 */
@OnlyIn(Dist.CLIENT)
public class EndermanCarriedAreaLayer extends RenderLayer<EnderMan, EndermanModel<EnderMan>> {
    // How far apart (in model units) to space out blocks that were 1 offset apart
    // in the original pickup cuboid, before the whole-cluster scale-down below is
    // applied. This is the same spacing vanilla's single block occupies.
    private static final float SPACING = 0.5F;

    private final BlockRenderDispatcher blockRenderDispatcher;

    public EndermanCarriedAreaLayer(RenderLayerParent<EnderMan, EndermanModel<EnderMan>> parent, BlockRenderDispatcher blockRenderDispatcher) {
        super(parent);
        this.blockRenderDispatcher = blockRenderDispatcher;
    }

    @Override
    public void render(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            EnderMan enderman,
            float limbSwing,
            float limbSwingAmount,
            float partialTick,
            float ageInTicks,
            float netHeadYaw,
            float headPitch
    ) {
        List<SyncCarriedBlocksPacket.Entry> entries = ClientCarriedBlocks.get(enderman.getId());
        if (entries.isEmpty()) {
            return;
        }

        // The furthest any offset reaches from the pickup centre on any axis - 0 for
        // a single block, 1 for a 3x3x3, 2 for a 5x5x5, etc.
        int radius = 0;
        for (SyncCarriedBlocksPacket.Entry entry : entries) {
            BlockPos offset = entry.offset();
            radius = Math.max(radius, Math.max(Math.abs(offset.getX()), Math.max(Math.abs(offset.getY()), Math.abs(offset.getZ()))));
        }
        // A cuboid of this radius is (2 * radius + 1) blocks wide. Scaling everything
        // down by that factor means the whole cluster - however big the pickup was -
        // always fits in the same amount of space vanilla's one block used to, rather
        // than growing arms-deep into (or past) the head as more blocks get added.
        float clusterScale = 1.0F / (2 * radius + 1);

        // Same base position + tilt vanilla's CarriedBlockLayer uses to sit its one
        // block just above and behind the head. Axis.XP (not Z!) is what vanilla
        // actually rotates on first - mixing that up is what made this look tilted
        // the wrong way before.
        poseStack.pushPose();
        poseStack.translate(0.0F, 0.6875F, -0.75F);
        poseStack.mulPose(Axis.XP.rotationDegrees(20.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(45.0F));
        poseStack.scale(clusterScale, clusterScale, clusterScale);

        for (SyncCarriedBlocksPacket.Entry entry : entries) {
            poseStack.pushPose();
            // Nudge this particular block away from the shared centre by whatever
            // offset it had in the original pickup cuboid, so the cluster's shape
            // roughly matches what was actually picked up instead of stacking every
            // block on top of the same point.
            //
            // Only Y is negated. Before any render layer runs, LivingEntityRenderer
            // has already applied a body-yaw rotation (turns with the entity - fine,
            // vanilla's own anchor below rides along with that the same way) composed
            // with a fixed scale(-1,-1,1). Working through that composition: a
            // rotation around Y never touches the Y component, so Y always ends up
            // negated regardless of which way the entity is facing - that part's a
            // real, fixed bug to correct. X and Z, though, get mixed together *by*
            // the yaw rotation in a way that depends on facing, with no fixed sign
            // either one "should" have - so there's nothing consistent to correct
            // there, and forcing a static negation on X would only coincidentally
            // look right at some facings and wrong at others.
            poseStack.translate(
                    -entry.offset().getX() * SPACING,
                    -entry.offset().getY() * SPACING,
                    entry.offset().getZ() * SPACING
            );
            // The remaining transform (translate/scale/rotate) is exactly what
            // vanilla's CarriedBlockLayer does for its single block.
            poseStack.translate(0.25F, 0.1875F, 0.25F);
            poseStack.scale(-0.5F, -0.5F, 0.5F);
            poseStack.mulPose(Axis.YP.rotationDegrees(0.0F));
            this.blockRenderDispatcher.renderSingleBlock(entry.state(), poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }

        poseStack.popPose();
    }
}
