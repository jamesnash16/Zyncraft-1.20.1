package uk.nashhost.zyncraft.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import uk.nashhost.zyncraft.Zyncraft;
import uk.nashhost.zyncraft.client.ClientCarriedBlocks;
import uk.nashhost.zyncraft.entity.goal.CarriedBlockArea;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A single-message SimpleChannel used only to mirror CarriedBlockArea onto the
 * client for rendering - see SyncCarriedBlocksPacket for why this is needed at
 * all instead of just reading the entity's data directly.
 * <p>
 * 1.20.1 predates the newer ChannelBuilder/payload-style networking API used by
 * the 1.21.1 sibling project - here a channel is built via
 * NetworkRegistry.newSimpleChannel instead, with a plain protocol-version string
 * both sides must agree on.
 */
public final class ModNetworking {
    private static final String PROTOCOL_VERSION = "1";

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(Zyncraft.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private ModNetworking() {
    }

    // Called once from Zyncraft's constructor. Registering a message just wires
    // up how to encode/decode/handle it - it doesn't send anything by itself.
    public static void register() {
        CHANNEL.messageBuilder(SyncCarriedBlocksPacket.class, 0)
                .encoder(SyncCarriedBlocksPacket::encode)
                .decoder(SyncCarriedBlocksPacket::decode)
                // consumerMainThread (rather than consumerNetworkThread) hops onto the
                // client's main thread before running the handler, which matters here
                // since ClientCarriedBlocks is read by the renderer every frame - we
                // don't want to be mutating that map from the network thread while the
                // render thread might be reading it.
                .consumerMainThread(ModNetworking::handleOnClient)
                .add();
    }

    // ClientCarriedBlocks only touches plain data types (BlockPos/BlockState/List),
    // so calling it here is safe even though this class is loaded on both sides -
    // this method just never actually gets invoked on a dedicated server, since the
    // server only ever sends this packet, never receives it.
    private static void handleOnClient(SyncCarriedBlocksPacket packet, Supplier<NetworkEvent.Context> context) {
        ClientCarriedBlocks.set(packet.entityId(), packet.entries());
    }

    /** Tells every client currently tracking (rendering) this Enderman what it's carrying. */
    public static void broadcastCarriedBlocks(EnderMan enderman, CarriedBlockArea area) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> enderman), toPacket(enderman, area));
    }

    /** Tells every client currently tracking this Enderman that it's no longer carrying anything. */
    public static void broadcastCleared(EnderMan enderman) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> enderman), new SyncCarriedBlocksPacket(enderman.getId(), List.of()));
    }

    /**
     * Catches up a single player who just started tracking an Enderman that was
     * already carrying something before that player was nearby to see it happen.
     */
    public static void sendCarriedBlocksTo(ServerPlayer player, EnderMan enderman, CarriedBlockArea area) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), toPacket(enderman, area));
    }

    private static SyncCarriedBlocksPacket toPacket(EnderMan enderman, CarriedBlockArea area) {
        List<SyncCarriedBlocksPacket.Entry> entries = new ArrayList<>(area.size());
        for (int i = 0; i < area.size(); i++) {
            entries.add(new SyncCarriedBlocksPacket.Entry(area.offset(i), area.state(i)));
        }
        return new SyncCarriedBlocksPacket(enderman.getId(), entries);
    }
}
