package com.mcpirates.airship.movement;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.AirshipStateMachine;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3d;

/**
 * Default movement: orbit the player target at {@link com.mcpirates.airship.kind.AirshipKind#orbitRadius()}.
 * Goal = current pos + look-ahead along (tangent + radial-error blend). Direction
 * (CCW/CW) is picked at PURSUE entry and flipped if the ship can't yaw to it.
 */
public final class OrbitMovement implements MovementBehavior {

    public static final OrbitMovement INSTANCE = new OrbitMovement();

    /** Look-ahead along the chosen orbit direction. Larger = smoother, more inertia. */
    private static final double ORBIT_LOOK_AHEAD = 18.0;
    /** Blend factor pulling the goal toward the target when outside orbitRadius. */
    private static final double ORBIT_RADIAL_GAIN = 0.8;
    /** Decisions of sustained heading-err > tolerance×3 before flipping orbit direction. */
    private static final int ORBIT_STUCK_DECISIONS = 8;

    /** Y offset above the target's eye level. Wide enough that the orbit clears the
     *  target's deck for cannon arcs / crossbow shots; tight enough that the ship stays
     *  in render range. */
    private static final double PURSUE_ALT_OFFSET = 12.0;

    private OrbitMovement() {}

    @Override
    public Goal computeGoal(Airship ship, Vector3d shipPos,
                            LivingEntity targetPlayer, SubLevel targetShip, long now) {
        if (targetPlayer == null) return null;
        double ftx = shipPos.x - targetPlayer.getX();
        double ftz = shipPos.z - targetPlayer.getZ();
        double r = Math.sqrt(ftx * ftx + ftz * ftz);
        if (r < 0.01) { ftx = 1.0; ftz = 0.0; r = 1.0; }
        double tanX = -ftz / r * ship.orbitDir;
        double tanZ = ftx / r * ship.orbitDir;
        double radInX = -ftx / r;
        double radInZ = -ftz / r;
        double orbitRadius = ship.kind.orbitRadius();
        double radialErr = (r - orbitRadius) / orbitRadius;
        double radialBlend = Math.max(-1.0, Math.min(1.0, radialErr)) * ORBIT_RADIAL_GAIN;
        double dirX = tanX + radInX * radialBlend;
        double dirZ = tanZ + radInZ * radialBlend;
        double dirLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (dirLen > 0.001) { dirX /= dirLen; dirZ /= dirLen; }
        return new Goal(shipPos.x + dirX * ORBIT_LOOK_AHEAD,
                        targetPlayer.getEyeY() + PURSUE_ALT_OFFSET,
                        shipPos.z + dirZ * ORBIT_LOOK_AHEAD);
    }

    @Override
    public void onEnterPursue(Airship ship, Vector3d shipPos,
                              LivingEntity targetPlayer, SubLevel targetShip) {
        if (targetPlayer == null) return;
        ship.orbitDir = AirshipStateMachine.pickOrbitDir(
                shipPos.x, shipPos.z,
                targetPlayer.getX(), targetPlayer.getZ(),
                ship.yawRadians());
        ship.orbitStuckDecisions = 0;
    }

    @Override
    public String debugOverlay(Airship ship) {
        return String.format(" orbit=%s stuck=%d/%d",
                ship.orbitDir > 0 ? "CCW" : "CW", ship.orbitStuckDecisions, ORBIT_STUCK_DECISIONS);
    }

    @Override
    public void onPursueDecision(Airship ship, double headingErrDeg) {
        if (Math.abs(headingErrDeg) > AirshipBrain.HEADING_TOLERANCE_DEG * 3.0) {
            ship.orbitStuckDecisions++;
            if (ship.orbitStuckDecisions >= ORBIT_STUCK_DECISIONS) {
                ship.orbitDir = -ship.orbitDir;
                ship.orbitStuckDecisions = 0;
                MCPirates.LOGGER.info(
                        "ship {} ({}): orbit flipped → dir={} (heading err sustained for {} decisions)",
                        ship.subLevel.getUniqueId(), ship.kind.name(), ship.orbitDir,
                        ORBIT_STUCK_DECISIONS);
            }
        } else {
            ship.orbitStuckDecisions = 0;
        }
    }
}
