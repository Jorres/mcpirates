package com.mcpirates.airship.interfaces;

import com.mcpirates.airship.Airship;
import com.mcpirates.airship.physics.PlateauTable.LiftSetting;

/**
 * Per-kind lift actuator. The brain decides what altitude it wants (via plateau-table
 * lookup → {@link LiftSetting}); the implementation decides how to drive whatever
 * hardware the ship owns — hot-air burners, propeller-based thrust, future lift schemes.
 *
 * <p>Sibling of {@link ShipControls}. Same boundary contract: the brain never touches
 * burner / throttle blocks directly, every change goes through this interface, so adding
 * a new lift scheme is a new implementation rather than a branch in the brain.
 */
public interface ShipLift {

    /** Drive the lift hardware to produce {@code setting}'s requested state. */
    void apply(Airship a, LiftSetting setting);

    /** Current balloon capacity in m³, or {@code -1} until a balloon attaches.
     *  Used by the brain to decide whether the plateau table can be rebuilt. */
    int queryBalloonCapacity(Airship a);

    /** How many independent lift units the assembly has. For hot-air kinds this is the
     *  burner count; total gas output = {@code count · volume · lever / 15}. The brain
     *  feeds this into the plateau-table sizing. */
    int burnerCount();

    /** Compact debug snapshot of the current lift state (lever / volume / etc.) for the
     *  brain's debug overlay. Default: empty string. */
    default String describe(Airship a) { return ""; }
}
