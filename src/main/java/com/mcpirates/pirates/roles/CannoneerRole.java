package com.mcpirates.pirates.roles;

import com.mcpirates.airship.Airship;
import com.mcpirates.pirates.CaptainSpawner.AnchoredEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.item.ItemStack;

/**
 * Unarmed pillager bound to one cannon mount. The role itself does nothing per tick —
 * the linkage between cannoneer and cannon is enforced inside the {@link
 * com.mcpirates.airship.kind.CombatBehavior} implementations, which query
 * {@link Airship#isMountManned(net.minecraft.core.BlockPos)} before aiming or firing
 * each mount. When the cannoneer dies, the lookup returns false and the cannon goes
 * silent — exactly the "kill the gunner, silence the gun" mechanic the design calls for.
 *
 * <p>Spawned by {@link com.mcpirates.pirates.CaptainSpawner} from the closest Create seat
 * to each cannon mount; the binding lives in {@link Airship#cannoneerByMount}.
 *
 * <p>Stateless singleton — every cannoneer shares this instance.
 */
public final class CannoneerRole implements PirateRole {

    public static final CannoneerRole INSTANCE = new CannoneerRole();

    private CannoneerRole() {}

    @Override public String name() { return "cannoneer"; }

    /** Unarmed — the cannon is the weapon. Empty stack also causes the spawner to skip
     *  the aggressive-pose flag so the gunner looks like crew, not a shooter. */
    @Override
    public ItemStack mainHandItem() { return ItemStack.EMPTY; }

    @Override
    public void tick(ServerLevel parentLevel, Airship ship, AnchoredEntity self, Pillager pillager,
                     ServerPlayer target, long now) {
        // intentional no-op
    }
}
