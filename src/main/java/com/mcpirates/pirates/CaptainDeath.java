package com.mcpirates.pirates;

import com.mcpirates.MCPirates;
import com.mcpirates.registry.MCPItems;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Drops a {@link com.mcpirates.registry.MCPItems#CAPTAIN_SEAL} when a captain-tagged
 * pillager dies. Lives outside the airship code so the airship pipeline doesn't carry
 * a hard-coupling to bounty mechanics.
 *
 * <h2>Plot-local vs world-rendered coordinates</h2>
 *
 * <p>Sable stores SubLevel contents in the same {@link ServerLevel} as everything else,
 * at "plot" coordinates — a chunk-aligned region kept out of the playable world. Entities
 * inside a SubLevel have {@code entity.level()} pointing at the regular ServerLevel and
 * {@code entity.position()} pointing at a plot-local coord. The visual position
 * (where the player sees them) is {@code subLevel.logicalPose().transformPosition(plotLocal)}.
 *
 * <p>If we spawn the seal ItemEntity at {@code victim.position()} (plot-local), it sits
 * inside the SubLevel's plot region — riding the airship as wreckage. Players would have
 * to climb the ship to pick it up.
 *
 * <p>If we spawn at the world-rendered position, it sits outside the plot — in normal
 * world space — and falls to the ground naturally for the player to collect. That's the
 * v0.1 behaviour: kill captain, ship sails on, loot falls.
 *
 * <h2>Why not a vanilla loot table</h2>
 *
 * <p>Vanilla loot tables key off {@link net.minecraft.world.entity.EntityType}. We'd
 * either have to modify the {@code minecraft:entities/pillager} loot table (hitting all
 * pillagers, including raid pillagers) or introduce a custom entity type for captains
 * (significant registration). An event handler with one tag check is the smallest
 * correct hammer for this single drop.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class CaptainDeath {

    private CaptainDeath() {}

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (!victim.getTags().contains(CaptainSpawner.CAPTAIN_TAG)) {
            return;
        }
        Level level = victim.level();
        if (level.isClientSide()) {
            return;
        }

        // Drop in the same Level the victim lived in. If the captain was riding a SubLevel,
        // shift the drop from plot-local to the world-rendered position so it falls to
        // real ground instead of riding the wreckage.
        Vec3 dropPos = victim.position();
        SubLevel containing = Sable.HELPER.getContaining(victim);
        if (containing != null) {
            dropPos = containing.logicalPose().transformPosition(victim.position());
        }

        ItemStack seal = new ItemStack(MCPItems.CAPTAIN_SEAL.get(), 1);
        ItemEntity drop = new ItemEntity(level, dropPos.x, dropPos.y + 0.5, dropPos.z, seal);
        drop.setDefaultPickUpDelay();
        level.addFreshEntity(drop);

        MCPirates.LOGGER.info(
                "captain {} died (plot-local={}, world-rendered={}); seal dropped at {} in {}",
                victim.getUUID(),
                victim.position(),
                containing == null ? "(no sublevel)" : dropPos,
                dropPos,
                level instanceof ServerLevel sl ? sl.dimension().location() : "(non-server)");
    }
}
