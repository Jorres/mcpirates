package com.mcpirates.pirates.roles;

import com.mcpirates.airship.Airship;
import com.mcpirates.pirates.CaptainSpawner.AnchoredEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Per-pirate behaviour driven by {@link com.mcpirates.pirates.PirateBrain} each server
 * tick. Pillagers stay {@code NoAi=true} because vanilla pathfinding fights Sable's
 * plot-position rebind; rotation/projectiles are written directly. Instances may carry
 * mutable per-pirate state, are not serialised, and reset on server restart.
 */
public interface PirateRole {

    /** {@code parentLevel}: world that holds the pillager (projectiles spawn here).
     *  Roles read the pillager's live {@code position()} — they ride Create SeatEntities,
     *  so world pos is authoritative without any plot-pos translation. */
    void tick(ServerLevel parentLevel, Airship ship, AnchoredEntity self, Pillager pillager,
              LivingEntity target, long now);

    /** Short label for log lines. */
    String name();

    /** Override with {@link ItemStack#EMPTY} for unarmed roles. */
    default ItemStack mainHandItem() { return new ItemStack(Items.CROSSBOW); }
}
