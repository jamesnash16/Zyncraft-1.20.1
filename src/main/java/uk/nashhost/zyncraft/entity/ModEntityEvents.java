package uk.nashhost.zyncraft.entity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import uk.nashhost.zyncraft.Zyncraft;
import uk.nashhost.zyncraft.entity.goal.CarriedBlockArea;
import uk.nashhost.zyncraft.entity.goal.EndermanLeaveBlockAreaGoal;
import uk.nashhost.zyncraft.entity.goal.EndermanTakeBlockAreaGoal;
import uk.nashhost.zyncraft.network.ModNetworking;

/**
 * Swaps vanilla's single-block Enderman pickup/place goals for our 3x3x3
 * versions on every Enderman as it joins a level.
 */
// @Mod.EventBusSubscriber auto-registers every @SubscribeEvent method below onto
// the Forge event bus, so we don't need an explicit registration call anywhere else.
@Mod.EventBusSubscriber(modid = Zyncraft.MOD_ID)
public class ModEntityEvents {

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        // This event fires for every entity, on both the client and the server, every
        // time it's added to a level - including every single chunk load, not just
        // first spawn. We only want to touch AI once, and only where AI actually runs,
        // so we bail out on the client and on anything that isn't an Enderman.
        // The `instanceof EnderMan enderman` pattern both checks the type and casts it
        // into a new `enderman` variable in one step (Java 16+ pattern matching).
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof EnderMan enderman)) {
            return;
        }

        // Every Mob has two goal lists: goalSelector (what it does - wander, attack,
        // pick up blocks...) and targetSelector (who it decides to attack). Both are
        // public fields on Mob, so we can reach in and edit them directly instead of
        // needing a Mixin.
        //
        // We can't reference EnderMan's inner goal classes by type (EndermanTakeBlockGoal
        // and EndermanLeaveBlockGoal are package-private, so writing their type name
        // wouldn't compile from our package), but comparing the class's *name* at
        // runtime works fine - reflection isn't blocked by that access restriction.
        // removeAllGoals takes a Predicate<Goal>: it removes every goal in the selector
        // that the predicate returns true for.
        enderman.goalSelector.removeAllGoals(goal ->
                goal.getClass().getSimpleName().equals("EndermanTakeBlockGoal")
                        || goal.getClass().getSimpleName().equals("EndermanLeaveBlockGoal"));

        // addGoal(priority, goal): lower priority numbers run first when multiple goals
        // could run at once. 10/11 match the priorities vanilla used for these two goals,
        // so we're not changing how they compete against the Enderman's other goals
        // (fleeing, attacking, wandering, etc.), just replacing their behaviour.
        enderman.goalSelector.addGoal(10, new EndermanLeaveBlockAreaGoal(enderman));
        enderman.goalSelector.addGoal(11, new EndermanTakeBlockAreaGoal(enderman));

        // Roll a permanent radius only the first time this Enderman is ever seen -
        // has() is backed by persistentData, which (unlike a plain Java field) does
        // survive a chunk unload/reload, so this genuinely only rolls once per
        // Enderman's lifetime rather than once per chunk load.
        // On every other join, vanilla's own attribute save/load already restores the
        // permanent modifiers assign() added, with no help needed from us - we still
        // call reapplyAttributes() anyway so that changing the *_PER_RADIUS constants
        // below and reloading takes effect on Endermen that already exist, not just
        // newly spawned ones (handy while tuning these values).
        if (!EndermanRadius.has(enderman)) {
            EndermanRadius.assign(enderman);
        } else {
            EndermanRadius.reapplyAttributes(enderman);
        }

        // The persistent radius tag above is what onEntitySize() below reads to pick
        // a hitbox scale, but Entity caches its EntityDimensions and only recomputes
        // them on a pose change (see refreshDimensions() callers) - without forcing
        // one here, a freshly assigned/reapplied radius wouldn't take effect on the
        // hitbox until the Enderman next changed pose on its own.
        enderman.refreshDimensions();
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        // Fires whenever a player comes into range of an entity (e.g. walking closer,
        // or just logging in near one). Without this, a player who wasn't nearby when
        // an Enderman picked up its cuboid would never get the SyncCarriedBlocksPacket
        // that caused that - broadcastCarriedBlocks only reaches players tracking the
        // Enderman *at the moment it's sent*, not ones who start tracking it later.
        if (!(event.getTarget() instanceof EnderMan enderman) || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (!CarriedBlockArea.has(enderman)) {
            return;
        }

        CarriedBlockArea area = CarriedBlockArea.load(enderman, enderman.level().registryAccess());
        ModNetworking.sendCarriedBlocksTo(player, enderman, area);
    }

    // 1.20.1 has no Attributes.SCALE (added in 1.20.5) to drive hitbox size off of,
    // so the bigger-radius-means-bigger-Enderman hitbox growth EndermanRadius does
    // is applied by hand here instead, via Forge's EntityEvent.Size. This fires on
    // both logical sides (needed so client and server agree on collision), unlike
    // the render-scale half of this in ModClientEvents' RenderLivingEvent.Pre
    // handler, which is client-only.
    @SubscribeEvent
    public static void onEntitySize(EntityEvent.Size event) {
        if (!(event.getEntity() instanceof EnderMan enderman) || !EndermanRadius.has(enderman)) {
            return;
        }

        float scale = EndermanRadius.scaleFor(EndermanRadius.get(enderman));
        EntityDimensions original = event.getOriginalSize();
        event.setNewSize(original.scale(scale), true);
    }
}
