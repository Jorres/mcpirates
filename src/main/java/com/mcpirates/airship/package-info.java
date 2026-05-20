/**
 * Airship runtime: discovers parked ships at outposts, lifts them into a Sable
 * SubLevel via {@link com.mcpirates.airship.AirshipAssembler}, then ticks the
 * state machine + movement controller in {@link com.mcpirates.airship.AirshipBrain}.
 *
 * <p>The parked-ship structure NBT itself lives under
 * {@code resources/data/mcpirates/structure/airship_small.nbt} and is wired into
 * worldgen via the {@code base_plate_with_airship} pool override (see the {@code
 * data/minecraft/worldgen/template_pool/pillager_outpost/base_plates.json}
 * datapack-side override). All of this is documented in {@code docs/decisions.md}.
 */
package com.mcpirates.airship;
