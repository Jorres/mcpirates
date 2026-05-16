package com.mcpirates.airship.kind;

import com.mcpirates.airship.Airship;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3d;

/**
 * Per-kind PURSUE movement strategy. The brain calls {@link #computeGoal} each decision
 * tick during PURSUE; the returned (x, z) is the world-frame horizontal goal the brain
 * steers toward using its generic yaw + clutch logic. Returning {@code null} signals
 * "no goal" (brain disengages clutches).
 *
 * <p>Two callbacks let an implementation maintain its own state without the brain
 * knowing about it: {@link #onEnterPursue} fires once on state transition, and
 * {@link #onPursueDecision} fires after every PURSUE decision with the heading error
 * the brain computed. Default both no-ops.
 *
 * <p>Both targets may be present (player riding their ship), one may be null. The kind
 * picks which one it prefers — orbit ignores the ship and circles the player; ram
 * prefers the ship and falls back to the player.
 */
public interface MovementBehavior {

    /** Horizontal goal in world coordinates. */
    record Goal(double x, double z) {}

    /** Decide where the ship should head this decision tick. Either target may be null;
     *  at least one is non-null when this is called. */
    Goal computeGoal(Airship ship, Vector3d shipPos,
                     LivingEntity targetPlayer, SubLevel targetShip, long now);

    /** State-entry hook on transitions INTO PURSUE. */
    default void onEnterPursue(Airship ship, Vector3d shipPos,
                               LivingEntity targetPlayer, SubLevel targetShip) {}

    /** State-exit hook on transitions OUT of PURSUE. Counterpart to onEnterPursue —
     *  ram movement uses it to disengage its forward clutch. */
    default void onExitPursue(Airship ship) {}

    /** Post-decision hook: brain's measured heading error after steering toward the goal. */
    default void onPursueDecision(Airship ship, double headingErrDeg) {}

    /** Movement-specific debug-overlay fragment, appended to the brain's PURSUE actionbar
     *  line. Default empty. OrbitMovement emits {@code " orbit=CCW stuck=N/8"} here. */
    default String debugOverlay(Airship ship) { return ""; }
}
