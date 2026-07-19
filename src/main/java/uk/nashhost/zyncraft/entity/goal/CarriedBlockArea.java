package uk.nashhost.zyncraft.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores the set of blocks an Enderman is carrying as offsets from a pickup
 * origin. Persisted on the entity's Forge {@code persistentData} tag so it
 * survives chunk unload/reload like vanilla's single carried block does.
 */
// Vanilla EnderMan only has room for one BlockState (a single synced entity-data
// field). It's a vanilla class, not ours, so we can't add a new field to it - Forge
// gives every Entity a spare CompoundTag (getPersistentData()) specifically for
// mods to stash extra data like this without needing a Mixin or full Capability.
// Forge automatically saves/loads that tag with the entity's own NBT (under a
// "ForgeData" wrapper), so anything we put in there survives a save/reload for free.
// This class is just a small in-memory holder plus the NBT read/write logic for it.
public final class CarriedBlockArea {

    // Namespacing the key avoids clashing with another mod's data in the same tag.
    private static final String TAG_KEY = "zyncraft:carried_area";

    // Two parallel lists instead of a Map<BlockPos, BlockState>: order doesn't matter
    // for what we do with this data, and parallel lists are simpler to (de)serialize.
    private final List<BlockPos> offsets = new ArrayList<>();
    private final List<BlockState> states = new ArrayList<>();

    public void add(BlockPos offset, BlockState state) {
        this.offsets.add(offset);
        this.states.add(state);
    }

    public boolean isEmpty() {
        return this.offsets.isEmpty();
    }

    public int size() {
        return this.offsets.size();
    }

    public BlockPos offset(int index) {
        return this.offsets.get(index);
    }

    public BlockState state(int index) {
        return this.states.get(index);
    }

    // Cheap existence check used by the goals' canUse() methods, so we don't have to
    // fully deserialize the NBT list just to ask "is this Enderman carrying anything?"
    public static boolean has(Entity entity) {
        return entity.getPersistentData().contains(TAG_KEY, Tag.TAG_LIST);
    }

    public static void clear(Entity entity) {
        entity.getPersistentData().remove(TAG_KEY);
    }

    // Writes this area into the entity's persistent tag as a list of
    // {x, y, z, state} compounds - one compound per carried block.
    public void save(Entity entity) {
        ListTag list = new ListTag();
        for (int i = 0; i < this.offsets.size(); i++) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", this.offsets.get(i).getX());
            entry.putInt("y", this.offsets.get(i).getY());
            entry.putInt("z", this.offsets.get(i).getZ());
            // NbtUtils.writeBlockState needs no registry lookup: a BlockState serializes
            // as just its block's registry name plus its property values (e.g. "facing=north").
            entry.put("state", NbtUtils.writeBlockState(this.states.get(i)));
            list.add(entry);
        }
        entity.getPersistentData().put(TAG_KEY, list);
    }

    // Reads the area back out. Reading a BlockState (unlike writing one) needs a
    // registry lookup, because the saved block name ("minecraft:stone") has to be
    // resolved back into the actual Block registry entry - hence the extra parameter
    // here that save() didn't need.
    public static CarriedBlockArea load(Entity entity, HolderLookup.Provider registries) {
        CarriedBlockArea area = new CarriedBlockArea();
        ListTag list = entity.getPersistentData().getList(TAG_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            BlockPos offset = new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
            BlockState state = NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), entry.getCompound("state"));
            area.add(offset, state);
        }
        return area;
    }
}
