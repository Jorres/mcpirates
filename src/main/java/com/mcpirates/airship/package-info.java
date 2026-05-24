/**
 * Airship runtime: discovers parked ships at outposts, lifts them into a Sable
 * SubLevel via {@link com.mcpirates.airship.AirshipAssembler}, then ticks the
 * state machine + movement controller in {@link com.mcpirates.airship.AirshipBrain}.
 *
 * <p>The parked-ship structure NBT itself lives under
 * {@code resources/data/mcpirates/structure/airship_small.nbt} and is wired into
 * worldgen via the {@code mcpirates:permitted_ship_outpost} structure type
 * (see {@link com.mcpirates.worldgen.PermittedShipOutpostStructure} +
 * {@link com.mcpirates.worldgen.OutpostPermits}). Generation is permit-gated:
 * structures only spawn at chunks stamped by {@code FurledBountyItem} when a
 * bounty scroll unfurls.
 */
package com.mcpirates.airship;
