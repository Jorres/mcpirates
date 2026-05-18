package com.mcpirates.airship.interfaces;

import com.mcpirates.airship.Airship;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3d;

/**
 * Per-kind PURSUE movement strategy. The brain calls {@link #computeGoal} each decision
 * tick during PURSUE; the returned 3D goal feeds both steering (XZ → heading error) and
 * lift (Y → altitude target via plateau lookup). Returning {@code null} signals "no goal"
 * (brain disengages clutches).
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

    /** 3D goal in world coordinates. Y is the target altitude the brain feeds to the
     *  plateau-table picker; the floor clamp (ground clearance) is applied by the brain
     *  uniformly across states. */
    record Goal(double x, double y, double z) {}

    /** Decide where the ship should head this decision tick. Either target may be null;
     *  at least one is non-null when this is called. */
    Goal computeGoal(Airship ship, Vector3d shipPos,
                     LivingEntity targetPlayer, SubLevel targetShip, long now);

    /** State-entry hook on transitions INTO PURSUE. */
    default void onEnterPursue(Airship ship, Vector3d shipPos,
                               LivingEntity targetPlayer, SubLevel targetShip) {}

    /** State-exit hook on transitions OUT of PURSUE. Default no-op. */
    default void onExitPursue(Airship ship) {}

    /** Post-decision hook: brain's measured heading error after steering toward the goal. */
    default void onPursueDecision(Airship ship, double headingErrDeg) {}

    /** Movement-specific debug-overlay fragment, appended to the brain's PURSUE actionbar
     *  line. Default empty. OrbitMovement emits {@code " orbit=CCW stuck=N/8"} here. */
    default String debugOverlay(Airship ship) { return ""; }
}
