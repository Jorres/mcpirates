package com.mcpirates.village;

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

        villager.setCustomName(Component.literal(FunnyNames.nextSheriffName(villager.getRandom())));
        villager.setCustomNameVisible(true);
    }
}

