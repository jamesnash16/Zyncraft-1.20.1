package uk.nashhost.zyncraft.client;

import uk.nashhost.zyncraft.network.SyncCarriedBlocksPacket;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side mirror of what each tracked Enderman is carrying, keyed by entity
 * id. Populated entirely by SyncCarriedBlocksPacket - EndermanCarriedAreaLayer
 * reads from this every frame. Nothing here ever touches the server's actual
 * CarriedBlockArea/persistentData directly.
 */
public final class ClientCarriedBlocks {
    private static final Map<Integer, List<SyncCarriedBlocksPacket.Entry>> CARRIED = new HashMap<>();

    private ClientCarriedBlocks() {
    }

    public static void set(int entityId, List<SyncCarriedBlocksPacket.Entry> entries) {
        if (entries.isEmpty()) {
            CARRIED.remove(entityId);
        } else {
            CARRIED.put(entityId, entries);
        }
    }

    public static List<SyncCarriedBlocksPacket.Entry> get(int entityId) {
        return CARRIED.getOrDefault(entityId, List.of());
    }
}
