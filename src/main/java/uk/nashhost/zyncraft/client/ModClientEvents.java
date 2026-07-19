package uk.nashhost.zyncraft.client;

import net.minecraft.client.renderer.entity.EndermanRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.CarriedBlockLayer;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import uk.nashhost.zyncraft.Zyncraft;

import java.lang.reflect.Field;
import java.util.List;

/**
 * value = Dist.CLIENT means this whole class - including anything it references,
 * like EndermanCarriedAreaLayer - is only ever loaded on the client, never on a
 * dedicated server. Same pattern Zyncraft.ClientModEvents already uses.
 */
@Mod.EventBusSubscriber(modid = Zyncraft.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ModClientEvents {

    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        // getEntityRenderer returns the already-constructed EndermanRenderer instance
        // (complete with vanilla's EnderEyesLayer and CarriedBlockLayer already added
        // to it).
        EndermanRenderer renderer = event.getEntityRenderer(EntityType.ENDERMAN);
        if (renderer == null) {
            return;
        }

        // We still set the vanilla single-block slot server-side (see
        // EndermanTakeBlockAreaGoal) purely so EndermanModel.carrying flips to true and
        // the arms raise up - but vanilla's own CarriedBlockLayer reads that exact same
        // slot to draw its one block, which would now double up with (and clip into)
        // our own cluster. Strip vanilla's layer out so only ours draws anything.
        removeVanillaCarriedBlockLayer(renderer);
        renderer.addLayer(new EndermanCarriedAreaLayer(renderer, event.getContext().getBlockRenderDispatcher()));
    }

    // LivingEntityRenderer.layers is `protected`, and we're neither in its package nor
    // a subclass of it, so there's no compilable way to reach in and remove from it -
    // reflection is the only option, same idea as the goalSelector.removeAllGoals
    // class-name trick in ModEntityEvents, just for a field instead of a method.
    private static void removeVanillaCarriedBlockLayer(EndermanRenderer renderer) {
        try {
            Field layersField = LivingEntityRenderer.class.getDeclaredField("layers");
            layersField.setAccessible(true);
            List<?> layers = (List<?>) layersField.get(renderer);
            layers.removeIf(layer -> layer instanceof CarriedBlockLayer);
        } catch (ReflectiveOperationException e) {
            Zyncraft.LOGGER.warn("Couldn't remove vanilla CarriedBlockLayer from EndermanRenderer", e);
        }
    }
}
