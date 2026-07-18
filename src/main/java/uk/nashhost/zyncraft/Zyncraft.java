package uk.nashhost.zyncraft;

import com.mojang.logging.LogUtils;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import uk.nashhost.zyncraft.item.ModItems;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(Zyncraft.MOD_ID)
public class Zyncraft
{
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "zyncraft";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    public Zyncraft(FMLJavaModLoadingContext context)
    {
        IEventBus modEventBus = context.getModEventBus();
        modEventBus.addListener(this::commonSetup);
        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);

        ModItems.register(modEventBus);
        // The Enderman rework (goals, EndermanRadius, networking, custom rendering)
        // and its sound effects are still 1.21.1-only at this point - they rely on
        // Attributes.SCALE (added in 1.20.5, doesn't exist here) and on Forge's
        // newer networking API (ChannelBuilder, changed shape around 1.20.2). Both
        // need real porting work, not just a recompile - see the 1.21.1 project's
        // ModNetworking/EndermanRadius for what has to be adapted before they can
        // be registered here too.

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ForgeConfigSpec so that Forge can create and load the config file for us
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {

    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(ModItems.ZYNDITE);
            event.accept(ModItems.RAW_ZYNDITE);
        }
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {

    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {

        }
    }
}
