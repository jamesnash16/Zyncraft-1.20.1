package uk.nashhost.zyncraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Sent server -> client so the render layer knows what an Enderman is carrying.
 * Forge's persistentData (where CarriedBlockArea actually lives - see
 * entity.goal.CarriedBlockArea) is only ever saved to disk, never networked, so
 * without this packet the client would have nothing to draw.
 * <p>
 * An empty {@code entries} list means "not carrying anything" - used both for an
 * Enderman that never picked anything up, and to tell the client to stop
 * rendering once it places its cuboid back down.
 */
public record SyncCarriedBlocksPacket(int entityId, List<Entry> entries) {

    public record Entry(BlockPos offset, BlockState state) {}

    public static void encode(SyncCarriedBlocksPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.entityId());
        buf.writeVarInt(packet.entries().size());
        for (Entry entry : packet.entries()) {
            // Offsets are always small (our take goal caps at MAX_RADIUS = 5), so a
            // byte per axis is plenty - no need for a full BlockPos encoding.
            buf.writeByte(entry.offset().getX());
            buf.writeByte(entry.offset().getY());
            buf.writeByte(entry.offset().getZ());
            // BLOCK_STATE_REGISTRY assigns every possible BlockState a stable integer
            // id - the same trick vanilla uses to send block states over the network -
            // so we send one varint instead of a full resource name + property list.
            buf.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(entry.state()));
        }
    }

    public static SyncCarriedBlocksPacket decode(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BlockPos offset = new BlockPos(buf.readByte(), buf.readByte(), buf.readByte());
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(buf.readVarInt());
            entries.add(new Entry(offset, state));
        }
        return new SyncCarriedBlocksPacket(entityId, entries);
    }
}
