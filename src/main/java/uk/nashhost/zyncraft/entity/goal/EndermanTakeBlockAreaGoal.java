package uk.nashhost.zyncraft.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.sounds.SoundSource;
import uk.nashhost.zyncraft.Zyncraft;
import uk.nashhost.zyncraft.entity.EndermanRadius;
import uk.nashhost.zyncraft.network.ModNetworking;
import uk.nashhost.zyncraft.sounds.ModSounds;

/**
 * Replaces vanilla's single-block {@code EndermanTakeBlockGoal}: picks a cuboid
 * (sized by that Enderman's permanent {@link EndermanRadius}, from a single
 * block up to 11x11x11) instead of the default single block and takes every
 * block inside it in one go.
 */
// A Goal is a unit of AI behaviour a Mob can run. Every tick, GoalSelector asks each
// registered Goal "canUse()?" and, once a Goal starts, calls its tick() repeatedly
// until canUse() (or canContinueToUse(), which defaults to canUse()) returns false.
// We don't override start()/stop() because this goal does all its work in one tick
// and never needs to run across multiple ticks.
public class EndermanTakeBlockAreaGoal extends Goal {
    private final EnderMan enderman;

    public EndermanTakeBlockAreaGoal(EnderMan enderman) {
        this.enderman = enderman;
    }

    @Override
    public boolean canUse() {
        // Don't start picking up a new area if we're already carrying one (either the
        // vanilla single-block slot or our own multi-block area).
        if (this.enderman.getCarriedBlock() != null || CarriedBlockArea.has(this.enderman)) {
            return false;
        }

        // Same gating vanilla uses: obey the mobGriefing gamerule, and only actually
        // try on 1 out of every `reducedTickDelay(20)` calls to canUse() so Endermen
        // don't attempt this every single tick. reducedTickDelay() shortens that delay
        // on servers running at a reduced tick rate (e.g. via /tick), matching vanilla.
        return this.enderman.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                && this.enderman.getRandom().nextInt(reducedTickDelay(20)) == 0;
    }

    @Override
    public void tick() {
        RandomSource random = this.enderman.getRandom();
        Level level = this.enderman.level();

        // Each Enderman gets a permanent radius assigned once, on spawn - see
        // EndermanRadius - rather than rolling a new one on every pickup attempt, so
        // a given Enderman's carrying capacity (and its size/health/attack damage,
        // scaled to match) stays consistent for its whole life.
        int radius = EndermanRadius.get(this.enderman);

        // Pick a random point in roughly a 4x3x4 box around the Enderman - the same
        // spread vanilla uses to choose where to look for a block to pick up. This is
        // the *top* candidate the cuboid search starts from below - not necessarily
        // where the cuboid ends up centred.
        int i = Mth.floor(this.enderman.getX() - 2.0 + random.nextDouble() * 4.0);
        int j = Mth.floor(this.enderman.getY() + random.nextDouble() * 3.0);
        int k = Mth.floor(this.enderman.getZ() - 2.0 + random.nextDouble() * 4.0);
        BlockPos lookAt = new BlockPos(i, j, k);

        // Raycast from the Enderman's eyes to the centre of the chosen point. If
        // something else (another block) is in the way and gets hit first, the ray
        // won't land on `lookAt` and we skip this attempt - this stops Endermen from
        // "reaching through walls" to grab a block they can't actually see.
        // We only raycast to this one point (not every position in the eventual
        // cuboid), which is a simplification versus checking line-of-sight to every
        // block individually.
        Vec3 eyes = new Vec3(this.enderman.getX(), this.enderman.getEyeY(), this.enderman.getZ());
        Vec3 target = Vec3.atCenterOf(lookAt);
        BlockHitResult hitResult = level.clip(new ClipContext(eyes, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, this.enderman));
        if (!hitResult.getBlockPos().equals(lookAt)) {
            Zyncraft.LOGGER.debug("[zyncraft] enderman {} take attempt aborted: line of sight blocked before reaching {} (hit {} instead)",
                    this.enderman.getId(), lookAt, hitResult.getBlockPos());
            return;
        }

        // Settle the cuboid downward onto whatever's actually there instead of
        // picking up a cuboid that's mostly (or entirely) air at the top. Starting
        // with the cuboid centred on lookAt, if its top layer is all air, slide the
        // whole cuboid down by one block and check again - repeat up to `radius`
        // times, so it never drifts more than a full radius below where it started.
        BlockPos center = lookAt;
        for (int shift = 0; shift < radius; shift++) {
            if (topLayerHasContent(level, center, radius)) {
                break;
            }
            center = center.below();
        }

        // Walk every block position in the 3x3x3 cuboid centred on `center` and keep
        // whichever ones are tagged enderman_holdable (the same tag vanilla checks for
        // its single-block pickup - dirt, sand, various ores, etc). Positions are
        // stored as offsets relative to `center` rather than absolute coordinates, so
        // the saved area can later be placed down anywhere, not just back at this spot.

        CarriedBlockArea area = new CarriedBlockArea();
        BlockPos from = center.offset(-radius, -radius, -radius);
        BlockPos to = center.offset(radius, radius, radius);
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            BlockState state = level.getBlockState(pos);
            // if (state.is(BlockTags.ENDERMAN_HOLDABLE)) {
            //     area.add(pos.subtract(center), state);
            // }
            // Let the enderman pick up any block other than bedrock by skipping ENDERMAN_HOLDABLE flag
            // and instead just check if block is bedrock
            if (!state.is(Blocks.BEDROCK)) {
                area.add(pos.subtract(center), state);
            }
        }

        // If nothing in the cuboid was holdable (e.g. it was all air or unbreakable
        // blocks), there's nothing to pick up this attempt - canUse() will let us
        // try again on a later tick.
        if (area.isEmpty()) {
            Zyncraft.LOGGER.debug("[zyncraft] enderman {} take attempt aborted: {}x{}x{} cuboid at {} was entirely bedrock/air, nothing to pick up",
                    this.enderman.getId(), radius * 2 + 1, radius * 2 + 1, radius * 2 + 1, center);
            return;
        }

        // Remove every block with neighbour updates suppressed (UPDATE_CLIENTS only,
        // not the UPDATE_NEIGHBORS that plain removeBlock(pos, false) would send).
        // Without this, removing a wall before the torch attached to it (or the
        // block under a standing torch, or one half of a bed/door) would make
        // vanilla think that block just lost its support mid-removal and
        // auto-break/drop it as an item - a duplicate, since we're also capturing
        // that exact block's state ourselves. Suppressing neighbour updates during
        // the removal means nothing reacts until every position in the cuboid is
        // already gone, so removal order stops mattering entirely.
        // getFluidState + createLegacyBlock mirrors what removeBlock(pos, false)
        // does internally, so a waterlogged block still correctly reverts to plain
        // water instead of dry air.
        for (int index = 0; index < area.size(); index++) {
            BlockPos pos = center.offset(area.offset(index));
            FluidState fluidState = level.getFluidState(pos);
            level.setBlock(pos, fluidState.createLegacyBlock(), Block.UPDATE_CLIENTS);
            level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(this.enderman, area.state(index)));
        }

        // Now that the whole cuboid is gone, let the surrounding world react
        // normally - this is what lets e.g. sand above the hole fall, or a torch
        // attached to a wall just *outside* the cuboid (so not something we
        // actually captured) correctly break, the same way it would if a player
        // had mined that wall by hand.
        for (int index = 0; index < area.size(); index++) {
            level.updateNeighborsAt(center.offset(area.offset(index)), Blocks.AIR);
        }

        // Persist the full set of carried blocks (see CarriedBlockArea), and also set
        // vanilla's own single-block slot to one representative block - vanilla's own
        // CarriedBlockLayer only ever renders getCarriedBlock(), so this keeps that
        // layer showing *something* even though our own EndermanCarriedAreaLayer is
        // what actually draws the full cuboid.
        area.save(this.enderman);
        this.enderman.setCarriedBlock(area.state(0));

        // persistentData never reaches the client on its own (see
        // SyncCarriedBlocksPacket), so without this broadcast nothing we just saved
        // would ever be visible to EndermanCarriedAreaLayer.
        ModNetworking.broadcastCarriedBlocks(this.enderman, area);

        // Audible feedback for the pickup - vanilla doesn't play anything distinct
        // for its own single-block version, so any SoundEvent works here; passing
        // null as the player broadcasts to everyone nearby instead of excluding one
        // specific player (which is what that parameter is for).
        level.playSound(null, this.enderman, ModSounds.ENDERMAN_PICKUP.get(), SoundSource.HOSTILE, 1.0F, 1.0F);

        Zyncraft.LOGGER.debug("[zyncraft] enderman {} picked up {} blocks (radius {}) centred on {}",
                this.enderman.getId(), area.size(), radius, center);
    }

    // Whether the horizontal slice at the top of a (radius*2+1)-wide cuboid centred
    // on `center` contains at least one non-air block. Used by the downward-settling
    // search in tick() to decide whether the cuboid needs to slide down further.
    private boolean topLayerHasContent(Level level, BlockPos center, int radius) {
        int topY = center.getY() + radius;
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                if (!level.getBlockState(new BlockPos(x, topY, z)).isAir()) {
                    return true;
                }
            }
        }
        return false;
    }
}
