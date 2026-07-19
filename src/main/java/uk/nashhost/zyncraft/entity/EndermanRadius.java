package uk.nashhost.zyncraft.entity;

import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.EnderMan;

import java.util.UUID;

/**
 * Assigns each Enderman a permanent, random pickup radius the first time it
 * spawns, then scales its size, health, and attack damage to match - a bigger
 * radius means a bigger, stronger Enderman. The take goal reads this instead of
 * rolling its own radius per attempt, so it stays the same for that Enderman's
 * whole life instead of changing every pickup.
 */
public final class EndermanRadius {
    // Upper bound (inclusive) for the radius rolled on spawn. Radius 0 = single
    // block, radius 5 = 11x11x11.
    public static final int MAX_RADIUS = 5;

    // How much bigger/stronger each point of radius makes an Enderman - all plain
    // additions on top of vanilla's own base values. Tune freely.
    private static final double SCALE_PER_RADIUS = 0.15;
    private static final double HEALTH_PER_RADIUS = 10.0;
    private static final double ATTACK_DAMAGE_PER_RADIUS = 1.5;

    // 1.20.1 doesn't have Attributes.SCALE (added in 1.20.5) or the
    // ResourceLocation-keyed AttributeModifier constructor that came with it -
    // AttributeModifier here is keyed by a plain UUID instead, and there's no
    // addOrReplacePermanentModifier, just addPermanentModifier (which throws if
    // the UUID's already present - see addModifier below for the manual replace).
    // Fixed UUIDs so re-applying on every level-join replaces the same modifier
    // instead of stacking a new one each time.
    private static final UUID HEALTH_MODIFIER_ID = UUID.fromString("8e1f0f2e-6b8a-4b3a-9a2c-7f2e6f0f2e01");
    private static final UUID ATTACK_DAMAGE_MODIFIER_ID = UUID.fromString("8e1f0f2e-6b8a-4b3a-9a2c-7f2e6f0f2e02");

    private static final String TAG_KEY = "zyncraft:radius";

    private EndermanRadius() {
    }

    public static boolean has(EnderMan enderman) {
        return enderman.getPersistentData().contains(TAG_KEY, Tag.TAG_INT);
    }

    public static int get(EnderMan enderman) {
        return enderman.getPersistentData().getInt(TAG_KEY);
    }

    // Rolls a new permanent radius and scales attributes to match, then heals to
    // full (since MAX_HEALTH just changed). Only call this for a genuinely new
    // Enderman - see ModEntityEvents, which checks has() first so an already
    // assigned radius never gets rerolled.
    public static void assign(EnderMan enderman) {
        int radius = enderman.getRandom().nextInt(MAX_RADIUS + 1);
        enderman.getPersistentData().putInt(TAG_KEY, radius);
        applyAttributes(enderman, radius);
        enderman.setHealth(enderman.getMaxHealth());
    }

    // Re-applies the attribute modifiers for an already-assigned radius. Safe to
    // call every time the Enderman (re)joins a level: addModifier below is
    // idempotent, and vanilla's own attribute saving already persists these
    // permanent modifiers across a save/reload by itself - this just needs to run
    // once per fresh Java object (e.g. after a chunk reload creates a new instance).
    public static void reapplyAttributes(EnderMan enderman) {
        applyAttributes(enderman, get(enderman));
    }

    // The size/render scale factor for a given radius - 1.0 is vanilla size. Used
    // both to resize the hitbox (see ModEntityEvents' EntityEvent.Size handler,
    // needed on both logical sides so client and server agree on collision) and to
    // scale the model when rendering (see ModClientEvents' RenderLivingEvent.Pre
    // handler, client-only). In 1.21.1 a single Attributes.SCALE modifier drove
    // both of these automatically; 1.20.1 has no such attribute, so they're done
    // by hand here instead.
    public static float scaleFor(int radius) {
        return (float) (1.0 + radius * SCALE_PER_RADIUS);
    }

    private static void applyAttributes(EnderMan enderman, int radius) {
        addModifier(enderman, Attributes.MAX_HEALTH, HEALTH_MODIFIER_ID, "zyncraft_radius_health", radius * HEALTH_PER_RADIUS);
        addModifier(enderman, Attributes.ATTACK_DAMAGE, ATTACK_DAMAGE_MODIFIER_ID, "zyncraft_radius_attack_damage", radius * ATTACK_DAMAGE_PER_RADIUS);
    }

    private static void addModifier(EnderMan enderman, Attribute attribute, UUID id, String name, double amount) {
        AttributeInstance instance = enderman.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        // addPermanentModifier throws IllegalArgumentException if a modifier with
        // this UUID is already applied, so remove any existing one first - this is
        // what makes re-applying on every join idempotent instead of erroring out.
        AttributeModifier existing = instance.getModifier(id);
        if (existing != null) {
            instance.removeModifier(existing);
        }
        instance.addPermanentModifier(new AttributeModifier(id, name, amount, AttributeModifier.Operation.ADDITION));
    }
}
