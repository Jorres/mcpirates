package com.mcpirates.registry;

import com.mcpirates.MCPirates;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Unit;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class MCPCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MCPirates.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + MCPirates.MOD_ID))
                    .icon(() -> new ItemStack(Items.CROSSBOW))
                    .displayItems((params, output) -> {
                        MCPItems.ITEMS.getEntries().forEach(item -> output.accept(item.get()));
                        ItemStack galleonScroll = new ItemStack(MCPItems.FURLED_BOUNTY.get());
                        galleonScroll.set(MCPDataComponents.IS_GALLEON_BOUNTY.get(), Unit.INSTANCE);
                        output.accept(galleonScroll);
                    })
                    .build());

    private MCPCreativeTabs() {}

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
