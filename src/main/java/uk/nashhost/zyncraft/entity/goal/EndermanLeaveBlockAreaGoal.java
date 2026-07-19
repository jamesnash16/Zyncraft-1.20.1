package uk.nashhost.zyncraft.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import uk.nashhost.zyncraft.Zyncraft;
import uk.nashhost.zyncraft.network.ModNetworking;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Replaces vanilla's single-block {@code EndermanLeaveBlockGoal}: places the
 * whole 3x3x3 cuboid the Enderman is carrying back down at once, or not at
 * all if any single position in the cuboid is obstructed.
 */
public class EndermanLeaveBlockAreaGoal extends Goal {
    private final EnderMan enderman;

    public EndermanLeaveBlockAreaGoal(EnderMan enderman) {
        this.enderman = enderman;
    }

    @Override
    public boolean canUse() {
        // Nothing to place if we're not carrying a saved area.
        if (!CarriedBlockArea.has(this.enderman)) {
            return false;
        }

        // Same gating as the take goal, but a much longer average delay (1-in-2000
        // instead of 1-in-20) - vanilla Endermen hold onto a block for a long time
        // before randomly deciding to put it back down somewhere.
        return this.enderman.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                && this.enderman.getRandom().nextInt(reducedTickDelay(200)) == 0;
    }

    @Override
    public void tick() {
        RandomSource random = this.enderman.getRandom();
        Level level = this.enderman.level();

        // Read the saved area back out of persistent data first - we need to know its
        // vertical extent before picking a centre, not after. registryAccess() is
        // needed here (see CarriedBlockArea.load) to resolve the saved block names
        // back into real Block references.
        CarriedBlockArea area = CarriedBlockArea.load(this.enderman, level.registryAccess());
        if (area.isEmpty()) {
            // Defensive cleanup: shouldn't normally happen since canUse() already
            // checked CarriedBlockArea.has(), but guards against an empty/corrupt tag.
            CarriedBlockArea.clear(this.enderman);
            this.enderman.setCarriedBlock(null);
            ModNetworking.broadcastCleared(this.enderman);
            return;
        }

        // minY: needed below to shift the placement centre so the structure's true
        // solid bottom (not just the smallest captured Y offset - see below) lands
        // at the search height, rather than the cuboid's middle.
        // structureRadius: how far the cuboid reaches from its own centre on any
        // axis (0 for a single block, 5 for an 11x11x11) - used below to widen the
        // search far enough to plausibly reach past a structure of this size.
        //
        // minY is deliberately computed only over solid (non-air) offsets, not every
        // captured offset. The take goal always captures a full dense cuboid, air
        // included - if that cuboid was picked up near the bottom of a superflat
        // world (solid ground there can be as little as 3-4 blocks thick, sitting
        // right on bedrock), most of the offsets below the real solid material are
        // just captured void, not ground. Including those in minY drags it down to
        // the bottom of that void instead of the bottom of the actual solid content,
        // which then shifts the whole structure up so its solid part floats several
        // blocks above true ground on every single placement attempt - this was the
        // actual cause of every "touch no existing terrain" failure in a superflat
        // test world, not the search area being too narrow.
        int minY = Integer.MAX_VALUE;
        int structureRadius = 0;
        for (int index = 0; index < area.size(); index++) {
            BlockPos offset = area.offset(index);
            structureRadius = Math.max(structureRadius, Math.max(Math.abs(offset.getX()), Math.max(Math.abs(offset.getY()), Math.abs(offset.getZ()))));
            if (!area.state(index).isAir()) {
                minY = Math.min(minY, offset.getY());
            }
        }
        if (minY == Integer.MAX_VALUE) {
            // The whole structure is air (e.g. picked up entirely from open sky) -
            // nothing solid to align a "bottom" to, so fall back to the full offset
            // range rather than leaving minY unset.
            for (int index = 0; index < area.size(); index++) {
                minY = Math.min(minY, area.offset(index).getY());
            }
        }

        // Vanilla's own single-block goal only ever searches about 1 block from the
        // Enderman's current position, because a single block always has somewhere
        // to rest nearby. That's nowhere near enough for us: the take goal just dug
        // a hole up to (2*structureRadius+1) blocks wide around wherever the
        // Enderman was standing, and this goal fires again while it's usually still
        // right next to (or inside) that same hole - a ±1 search can only ever find
        // more of the cavity it just carved out, never anything solid to anchor to
        // (see touchesExistingTerrain). Searching out to structureRadius + 2 instead
        // - comfortably past the excavated cuboid's own footprint - gives a real
        // chance of reaching undisturbed terrain on some attempt without the
        // Enderman needing to physically walk there first.
        int searchSpread = structureRadius + 2;
        int i = Mth.floor(this.enderman.getX() - searchSpread + random.nextDouble() * (searchSpread * 2));
        // Vertical search deliberately stays vanilla's original 0-2 *above* the
        // Enderman's feet only, never below - widening it below feet was tried and
        // reverted: on a superflat world the "ground" the Enderman stands on can be
        // as little as 3-4 blocks thick sitting right on top of unbreakable bedrock,
        // so a downward search routinely drove large structures straight into that
        // thin ground/bedrock and failed OCCUPIED almost every attempt. Escaping a
        // self-dug cavity only ever needed the horizontal widening above; there's no
        // matching need to search below feet.
        int j = Mth.floor(this.enderman.getY() + random.nextDouble() * 2.0) - minY;
        int k = Mth.floor(this.enderman.getZ() - searchSpread + random.nextDouble() * (searchSpread * 2));
        BlockPos center = new BlockPos(i, j, k);

        // Every position just needs to be clear and entity-free - see canPlaceAt().
        // There's no per-position ground-support requirement: the Enderman is
        // dropping the whole cuboid back down as one unit, not stacking individual
        // blocks that each need something solid directly beneath them, so a bottom
        // layer that overhangs a ledge or gap is fine as long as the structure as a
        // whole is anchored to something real - see touchesExistingTerrain below.
        for (int index = 0; index < area.size(); index++) {
            BlockPos pos = center.offset(area.offset(index));
            PlacementFailure failure = canPlaceAt(level, pos);
            if (failure != null) {
                // Still all-or-nothing: one bad spot means we place nothing this
                // attempt and try again later, rather than dropping a gappy structure.
                Zyncraft.LOGGER.debug("[zyncraft] enderman {} leave attempt aborted: {} at {} (centre {}, {} blocks)",
                        this.enderman.getId(), failure, pos, center, area.size());
                return;
            }
        }

        // Endermen teleport freely, including straight up into open sky, completely
        // unrelated to this goal - without this check, a "leave block" attempt that
        // happens to fire while mid-teleport would find a perfectly clear destination
        // (empty air has no occupied blocks or entities in it) and drop the whole
        // carried structure floating there, disconnected from any terrain. Requiring
        // at least one point of contact anywhere along the structure's outer surface
        // rules that out while still allowing the overhang/gap case above.
        if (!touchesExistingTerrain(level, area, center)) {
            Zyncraft.LOGGER.debug("[zyncraft] enderman {} leave attempt aborted: {} blocks centred on {} touch no existing terrain",
                    this.enderman.getId(), area.size(), center);
            return;
        }

        // Place bottom-to-top so lower blocks exist in the world before upper ones
        // ask updateFromNeighbourShapes for their connected shape, and fire the
        // matching GameEvent (mirrors what removeBlock did in the take goal).
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < area.size(); index++) {
            order.add(index);
        }
        order.sort(Comparator.comparingInt(index -> area.offset(index).getY()));

        for (int index : order) {
            BlockPos pos = center.offset(area.offset(index));
            // updateFromNeighbourShapes lets context-sensitive blocks (fences, walls,
            // stairs, etc.) pick the correct connected shape for their new neighbours
            // instead of just plopping down whatever shape they had when picked up.
            // This only reads the world state already sitting there (earlier blocks
            // from this same bottom-to-top loop) - it doesn't depend on the
            // UPDATE_NEIGHBORS flag below at all.
            BlockState state = Block.updateFromNeighbourShapes(area.state(index), level, pos);
            // UPDATE_CLIENTS only, not the UPDATE_NEIGHBORS that flag 3 would add.
            // Placing a torch before the wall it leans against (or one half of a
            // bed/door before the other) would otherwise let it immediately fail its
            // own canSurvive check and pop off as a dropped item, before the rest of
            // the cuboid it depends on has even been placed yet.
            level.setBlock(pos, state, Block.UPDATE_CLIENTS);
            level.gameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Context.of(this.enderman, state));
        }

        // Everything's down now, so let the whole cuboid react to the world (and the
        // world react to it) normally - this is what finally lets torches/beds/doors
        // confirm they can survive, redstone connect, etc, now that their support
        // actually exists.
        for (int index : order) {
            BlockPos pos = center.offset(area.offset(index));
            level.updateNeighborsAt(pos, level.getBlockState(pos).getBlock());
        }

        // Done carrying - clear both our storage and vanilla's single-block slot, and
        // tell clients to stop rendering the cuboid above its head.
        CarriedBlockArea.clear(this.enderman);
        this.enderman.setCarriedBlock(null);
        ModNetworking.broadcastCleared(this.enderman);

        Zyncraft.LOGGER.debug("[zyncraft] enderman {} placed {} blocks centred on {}",
                this.enderman.getId(), area.size(), center);
    }

    // Mirrors the entity/occupancy checks vanilla's EndermanLeaveBlockGoal does for
    // its one block, just applied per-position across the whole cuboid - there's no
    // ground-support check here at all (see the comment above the validation loop
    // in tick()).
    // Returns null for "fine to place here", or which specific check rejected it.
    @Nullable
    private PlacementFailure canPlaceAt(Level level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()) {
            // Something's already occupying this spot - don't overwrite it.
            return PlacementFailure.OCCUPIED;
        }

        // getEntities(...).isEmpty() checks that no player, mob, or item entity is
        // currently standing/sitting in that exact block space, so we don't place a
        // block through/inside something.
        if (!level.getEntities(this.enderman, AABB.unitCubeFromLowerCorner(Vec3.atLowerCornerOf(pos))).isEmpty()) {
            return PlacementFailure.BLOCKED_BY_ENTITY;
        }

        return null;
    }

    // Whether the destination cuboid touches at least one pre-existing solid block
    // anywhere along its outer surface - any of the 6 faces, not just below. Only
    // one point of contact anywhere is required, so this doesn't fight the overhang
    // case canPlaceAt already allows; it just rules out the structure landing
    // somewhere with nothing solid next to it at all (see the comment above the
    // call to this in tick()).
    private boolean touchesExistingTerrain(Level level, CarriedBlockArea area, BlockPos center) {
        Set<BlockPos> offsets = new HashSet<>();
        for (int index = 0; index < area.size(); index++) {
            offsets.add(area.offset(index));
        }

        for (int index = 0; index < area.size(); index++) {
            if (area.state(index).isAir()) {
                // The take goal captures a dense cuboid - air pockets included, not
                // just holdable blocks - so this offset can itself be empty. An air
                // cell touching real terrain isn't actually anchored to anything; only
                // the structure's own solid blocks count as points of contact.
                continue;
            }

            BlockPos offset = area.offset(index);
            for (Direction direction : Direction.values()) {
                BlockPos neighborOffset = offset.relative(direction);
                if (offsets.contains(neighborOffset)) {
                    // Still inside the structure itself, not existing terrain.
                    continue;
                }
                if (!level.getBlockState(center.offset(neighborOffset)).isAir()) {
                    return true;
                }
            }
        }

        return false;
    }

    private enum PlacementFailure {
        OCCUPIED,
        BLOCKED_BY_ENTITY
    }
}
