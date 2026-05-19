package com.mcpirates.registry;

import com.mcpirates.MCPirates;
import com.mcpirates.village.sheriff.SheriffMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * {@link #SHERIFF} — opened from {@code SheriffInteract} on right-click. Server passes
 * the villager's entity id through the extra-data {@code RegistryFriendlyByteBuf};
 * client resolves it via {@code playerInv.player.level().getEntity(id)} to wire up
 * the state-driven slot rules.
 */
public final class MCPMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MCPirates.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<SheriffMenu>> SHERIFF =
            MENUS.register("sheriff", () -> IMenuTypeExtension.create(SheriffMenu::fromNetwork));

    private MCPMenuTypes() {}

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}
