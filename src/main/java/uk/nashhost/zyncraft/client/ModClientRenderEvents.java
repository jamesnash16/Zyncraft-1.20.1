package uk.nashhost.zyncraft.client;

import net.minecraft.world.entity.monster.EnderMan;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import uk.nashhost.zyncraft.Zyncraft;
import uk.nashhost.zyncraft.entity.EndermanRadius;

/**
 * Scales an Enderman's rendered model to match its permanent {@link EndermanRadius}
 * - a bigger pickup radius means a visually bigger Enderman. In 1.21.1 this falls
 * out of the Attributes.SCALE modifier EndermanRadius applies automatically; 1.20.1
 * has no such attribute (added in 1.20.5), so the visual half of that scaling is
 * done by hand here instead. The hitbox half is handled separately, server- and
 * client-side, by ModEntityEvents' EntityEvent.Size handler.
 * <p>
 * Unlike ModClientEvents (which subscribes to the MOD bus for setup-time events),
 * this fires every frame on the main Forge event bus, so it needs its own
 * class-level bus configuration rather than sharing ModClientEvents'.
 */
@Mod.EventBusSubscriber(modid = Zyncraft.MOD_ID, value = Dist.CLIENT)
public class ModClientRenderEvents {

    @SubscribeEvent
    public static void onRenderEndermanPre(RenderLivingEvent.Pre<?, ?> event) {
        if (!(event.getEntity() instanceof EnderMan enderman) || !EndermanRadius.has(enderman)) {
            return;
        }

        float scale = EndermanRadius.scaleFor(EndermanRadius.get(enderman));
        event.getPoseStack().scale(scale, scale, scale);
    }
}
