package uk.nashhost.zyncraft.item;

import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import uk.nashhost.zyncraft.Zyncraft;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Zyncraft.MOD_ID);

    public static final RegistryObject<Item> ZYNDITE = ITEMS.register("zyndite",
            () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> RAW_ZYNDITE = ITEMS.register("raw_zyndite",
            () -> new Item(new Item.Properties()));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
