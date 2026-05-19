package com.mcpirates.airship.ships.ramship;

import com.mcpirates.airship.Airship;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.ShipTelemetry;
import com.mcpirates.airship.common.TurnPolicy;
import com.mcpirates.airship.hardware.ClutchLevers;
import com.mcpirates.airship.hardware.Propellers;
import com.mcpirates.airship.interfaces.ShipControls;
import dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlock;
import dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Vector3d;

/**
 * Steering for ships with a third forward-axis propeller (currently ramship only).
 *
 * <p><b>REVERSED is relative to NBT default.</b> Ramship NBT bakes
 * {@code reversed=true} as the forward-push orientation; the controller captures the
 * NBT defaults at construction and writes deltas. Writing absolute REVERSED clobbers
 * the intentional orientation (ship moves south while facing north).
 *
 * <p>Regime selection (ALIGNED / TANK_STEER / COUNTER_ROTATE) comes from the shared
 * {@link TurnPolicy}; this class only maps each regime to the three-propeller hardware
 * (forward prop, two outboards) and layers the retreat phase on top:
 * <ul>
 *   <li><b>Retreat</b> (post-collision): all three propellers flipped off NBT default,
 *       all three clutches engaged — straight reverse. Heading error is ignored for the
 *       retreat duration; ramship coasts backward, then normal regimes resume.</li>
 *   <li><b>ALIGNED</b>: all three at NBT default, all clutches engaged.</li>
 *   <li><b>TANK_STEER</b>: outboards at NBT default; outside outboard engaged, inside
 *       outboard disengaged. Forward prop stays on — continuous forward thrust + yaw.</li>
 *   <li><b>COUNTER_ROTATE</b>: outboards anti-parallel (one flipped REVERSED) for pure
 *       pivot. Forward clutch off so axial thrust doesn't fight the pivot.</li>
 * </ul>
 */
public final class RamControls implements ShipControls {

    // Steering regime selection (aligned / tank-steer / counter-rotate) lives in the
    // shared TurnPolicy class — every ship picks the same regime for the same error.

    /** Retreat phase duration. ~12s at 20 TPS — long enough for visible separation from
     *  the victim hull while still inside {@code DISENGAGE_RANGE} (100 blocks). */
    private static final int RETREAT_TICKS = 120;
    /** Per-sample (3-tick) decrease in center-to-center distance that counts as "still
     *  closing". Below this the ramship is stuck against the contact instead of penetrating.
     *  0.3 b/3-tick = 0.1 b/tick — well under cruise (0.17) but above physics jitter. */
    private static final double DIST_DECREASE_MIN = 0.3;
    /** Consecutive samples of bbox overlap with stalled progress before retreat arms.
     *  10 × 3-tick = 30 ticks ≈ 1.5s — long enough to confirm sustained contact and
     *  let a glancing pass clear before firing, while still well under the retreat
     *  duration so the ramship doesn't get stuck in a grinding stalemate. */
    private static final int STUCK_SAMPLES_THRESHOLD = 10;

    private final BlockPos slLeftClutchLever;
    private final BlockPos slRightClutchLever;
    private final BlockPos slForwardClutchLever;
    private final BlockPos slLeftPropeller;
    private final BlockPos slRightPropeller;
    private final BlockPos slForwardPropeller;

    /** NBT-default REVERSED for each propeller. Passed in by the kind because the brain
     *  mutates the live block state during steering, so an at-rehydrate block read would
     *  yield a wrong default. */
    private final boolean nbtReversedL;
    private final boolean nbtReversedR;
    private final boolean nbtReversedF;

    private long retreatUntilTick = Long.MIN_VALUE;
    private double lastDistToContact = Double.NaN;
    private int stuckSamples = 0;

    public RamControls(BlockPos slLeftClutchLever,
                       BlockPos slRightClutchLever,
                       BlockPos slForwardClutchLever,
                       BlockPos slLeftPropeller,
                       BlockPos slRightPropeller,
                       BlockPos slForwardPropeller,
                       boolean nbtReversedL,
                       boolean nbtReversedR,
                       boolean nbtReversedF) {
        this.slLeftClutchLever = slLeftClutchLever;
        this.slRightClutchLever = slRightClutchLever;
        this.slForwardClutchLever = slForwardClutchLever;
        this.slLeftPropeller = slLeftPropeller;
        this.slRightPropeller = slRightPropeller;
        this.slForwardPropeller = slForwardPropeller;
        this.nbtReversedL = nbtReversedL;
        this.nbtReversedR = nbtReversedR;
        this.nbtReversedF = nbtReversedF;
    }

    @Override
    public void applySteering(Airship a, double headingErrRad) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return;

        long now = a.parentLevel.getGameTime();
        sampleAndDetectCollision(a, now);

        boolean leftReversed, rightReversed, forwardReversed;
        boolean leftEngaged, rightEngaged, forwardEngaged;

        if (now < retreatUntilTick) {
            // Retreat: flip all three off NBT default + engage all clutches → straight reverse.
            leftReversed = !nbtReversedL;
            rightReversed = !nbtReversedR;
            forwardReversed = !nbtReversedF;
            leftEngaged = true;
            rightEngaged = true;
            forwardEngaged = true;
        } else {
            double yawRate = ShipTelemetry.angularVelocity(a).y;
            TurnPolicy.Decision d = TurnPolicy.decide(headingErrRad, yawRate);
            forwardReversed = nbtReversedF;
            switch (d.regime()) {
                case ALIGNED -> {
                    // All three forward at NBT default, all clutches engaged.
                    leftReversed = nbtReversedL;
                    rightReversed = nbtReversedR;
                    leftEngaged = true;
                    rightEngaged = true;
                    forwardEngaged = true;
                }
                case TANK_STEER -> {
                    // Outside outboard pushes forward, inside outboard disengages, forward
                    // prop stays on. yawDir = +1 (CW): keep LEFT engaged, drop RIGHT.
                    leftReversed = nbtReversedL;
                    rightReversed = nbtReversedR;
                    leftEngaged = d.yawDir() > 0;
                    rightEngaged = d.yawDir() < 0;
                    forwardEngaged = true;
                }
                case COUNTER_ROTATE -> {
                    // Outboards anti-parallel for pure pivot; forward prop off so its
                    // axial thrust doesn't fight the pivot direction.
                    // yawDir = +1 (CW): LEFT forward, RIGHT reversed.
                    if (d.yawDir() > 0) {
                        leftReversed = nbtReversedL;
                        rightReversed = !nbtReversedR;
                    } else {
                        leftReversed = !nbtReversedL;
                        rightReversed = nbtReversedR;
                    }
                    leftEngaged = true;
                    rightEngaged = true;
                    forwardEngaged = false;
                }
                default -> throw new IllegalStateException("unreachable regime: " + d.regime());
            }
        }

        // Clutch levers: powered=true DIS-engages.
        ClutchLevers.setPowered(subLevel, slLeftClutchLever, !leftEngaged);
        ClutchLevers.setPowered(subLevel, slRightClutchLever, !rightEngaged);
        ClutchLevers.setPowered(subLevel, slForwardClutchLever, !forwardEngaged);
        Propellers.setReversed(subLevel, slLeftPropeller, leftReversed);
        Propellers.setReversed(subLevel, slRightPropeller, rightReversed);
        Propellers.setReversed(subLevel, slForwardPropeller, forwardReversed);
    }

    /** Arm retreat when our bbox overlaps another SubLevel AND distance-to-it stops
     *  meaningfully decreasing — that's the "stuck against the victim" pattern, where
     *  velocity-drop wouldn't fire because the deceleration is too gradual. Runs from both
     *  applySteering and release so the arrival-radius window stays sampled. */
    private void sampleAndDetectCollision(Airship a, long now) {
        if (a.state != AirshipBrain.State.PURSUE || now < retreatUntilTick) {
            stuckSamples = 0;
            lastDistToContact = Double.NaN;
            return;
        }
        SubLevel contact = findOverlappingSubLevel(a);
        if (contact == null) {
            stuckSamples = 0;
            lastDistToContact = Double.NaN;
            return;
        }
        double dist = centerDistance(a.subLevel, contact);
        if (Double.isNaN(lastDistToContact)
                || dist < lastDistToContact - DIST_DECREASE_MIN) {
            // Meaningful closing: we're still penetrating into the contact.
            lastDistToContact = dist;
            stuckSamples = 0;
        } else {
            // Bbox overlap with stalled progress = grinding against the hull.
            stuckSamples++;
            if (stuckSamples >= STUCK_SAMPLES_THRESHOLD) {
                retreatUntilTick = now + RETREAT_TICKS;
                com.mcpirates.MCPirates.LOGGER.info(
                        "ramship {} retreat ARMED at t={}: stuck for {} samples in bbox of {} at dist={}",
                        a.subLevel.getUniqueId(), now, stuckSamples,
                        contact.getUniqueId(), String.format("%.2f", dist));
                stuckSamples = 0;
                lastDistToContact = Double.NaN;
            }
        }
    }

    /** First other SubLevel whose bbox intersects ours, or null. */
    private static SubLevel findOverlappingSubLevel(Airship a) {
        SubLevelContainer container = SubLevelContainer.getContainer(a.parentLevel);
        if (container == null) return null;
        BoundingBox3dc myBox = a.subLevel.boundingBox();
        if (myBox == null) return null;
        for (SubLevel other : container.getAllSubLevels()) {
            if (other == a.subLevel || other.isRemoved()) continue;
            BoundingBox3dc theirBox = other.boundingBox();
            if (theirBox != null && myBox.intersects(theirBox)) return other;
        }
        return null;
    }

    /** Horizontal center-to-center distance between two SubLevels. */
    private static double centerDistance(SubLevel x, SubLevel y) {
        Vector3d xPos = x.logicalPose().position();
        Vector3d yPos = y.logicalPose().position();
        double dx = xPos.x - yPos.x;
        double dz = xPos.z - yPos.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Signed projection of velocity onto the ship's world-forward axis (blocks/tick).
     *  Positive = charging; negative = retreating. */
    private static double forwardSpeed(Airship a) {
        Vector3d vel = ShipTelemetry.velocity(a);
        Vector3d worldFwd = a.subLevel.logicalPose().orientation()
                .transform(new Vector3d(a.shipLocalForward), new Vector3d());
        return vel.x * worldFwd.x + vel.z * worldFwd.z;
    }

    @Override
    public String diagnostics(Airship a) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return "";
        long now = a.parentLevel.getGameTime();
        String phase = now < retreatUntilTick
                ? String.format("retreat=%d", retreatUntilTick - now)
                : String.format("fwd=%.3f stuck=%d/%d distToContact=%s",
                        forwardSpeed(a), stuckSamples, STUCK_SAMPLES_THRESHOLD,
                        Double.isNaN(lastDistToContact) ? "—" : String.format("%.2f", lastDistToContact));
        return String.format(
                "%s propL=%s propR=%s propF=%s clutchL=%s clutchR=%s clutchF=%s nbtRev=(L=%s,R=%s,F=%s)",
                phase,
                describeProp(subLevel, slLeftPropeller),
                describeProp(subLevel, slRightPropeller),
                describeProp(subLevel, slForwardPropeller),
                describeLever(subLevel, slLeftClutchLever),
                describeLever(subLevel, slRightClutchLever),
                describeLever(subLevel, slForwardClutchLever),
                nbtReversedL, nbtReversedR, nbtReversedF);
    }

    private static String describeProp(Level subLevel, BlockPos pos) {
        if (pos == null) return "—";
        BlockEntity be = subLevel.getBlockEntity(pos);
        if (!(be instanceof BasePropellerBlockEntity bpe)) {
            return "no-prop-be@" + pos.toShortString();
        }
        boolean reversed = subLevel.getBlockState(pos).getValue(BasePropellerBlock.REVERSED);
        return String.format("(thrust=%.2f rev=%s active=%s)",
                bpe.getThrust(), reversed, bpe.isActive());
    }

    private static String describeLever(Level subLevel, BlockPos pos) {
        if (pos == null) return "—";
        BlockState state = subLevel.getBlockState(pos);
        var pwr = BlockStateProperties.POWERED;
        return state.hasProperty(pwr)
                ? String.format("(powered=%s)", state.getValue(pwr))
                : "(no-powered-prop)";
    }

    @Override
    public void release(Airship a) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return;
        long now = a.parentLevel.getGameTime();
        // Sample inside arrival-radius release too — that's exactly when contact happens
        // and applySteering isn't being called.
        if (a.state == AirshipBrain.State.PURSUE) {
            sampleAndDetectCollision(a, now);
        }
        // Honor retreat through arrival-radius release windows — a vanilla release here
        // would strip the reverse-thrust pattern mid-collision. Any non-PURSUE state means
        // the chase context is gone, so retreat drops.
        if (a.state == AirshipBrain.State.PURSUE && now < retreatUntilTick) {
            ClutchLevers.setPowered(subLevel, slLeftClutchLever, false);
            ClutchLevers.setPowered(subLevel, slRightClutchLever, false);
            ClutchLevers.setPowered(subLevel, slForwardClutchLever, false);
            Propellers.setReversed(subLevel, slLeftPropeller, !nbtReversedL);
            Propellers.setReversed(subLevel, slRightPropeller, !nbtReversedR);
            Propellers.setReversed(subLevel, slForwardPropeller, !nbtReversedF);
            return;
        }
        if (retreatUntilTick != Long.MIN_VALUE) {
            com.mcpirates.MCPirates.LOGGER.info(
                    "ramship {} retreat DROPPED at t={} (state={})",
                    a.subLevel.getUniqueId(), now, a.state);
            retreatUntilTick = Long.MIN_VALUE;
        }
        ClutchLevers.setPowered(subLevel, slLeftClutchLever, /*powered=disengaged=*/true);
        ClutchLevers.setPowered(subLevel, slRightClutchLever, true);
        ClutchLevers.setPowered(subLevel, slForwardClutchLever, true);
        // Restore NBT default so the next engage starts from a clean forward-thrust state.
        Propellers.setReversed(subLevel, slLeftPropeller, nbtReversedL);
        Propellers.setReversed(subLevel, slRightPropeller, nbtReversedR);
        Propellers.setReversed(subLevel, slForwardPropeller, nbtReversedF);
    }

    @Override
    public boolean isActive(Airship a) {
        Level subLevel = a.subLevel.getLevel();
        if (subLevel == null) return false;
        return ClutchLevers.isEngaged(subLevel, slLeftClutchLever)
                || ClutchLevers.isEngaged(subLevel, slRightClutchLever)
                || ClutchLevers.isEngaged(subLevel, slForwardClutchLever);
    }
}
