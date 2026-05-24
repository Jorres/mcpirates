package com.mcpirates.village;

import com.mcpirates.MCPirates;
import com.mcpirates.util.FunnyNames;
import com.mcpirates.registry.MCPVillagerProfessions;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

public final class SheriffNameAssigner {
    private SheriffNameAssigner() {}

    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }
        if (villager.level().isClientSide()) {
            return;
        }
        if (villager.getCustomName() != null) {
            return;
        }
        if (villager.getVillagerData().getProfession() != MCPVillagerProfessions.SHERIFF.get()) {
            return;
        }

        String name = FunnyNames.nextSheriffName(villager.getRandom());
        villager.setCustomName(Component.literal(name));
        villager.setCustomNameVisible(true);
        MCPirates.LOGGER.info("named sheriff villager {} → '{}'", villager.getUUID(), name);
    }
}

