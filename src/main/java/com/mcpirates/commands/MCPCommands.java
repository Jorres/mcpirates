package com.mcpirates.commands;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.AirshipBrain;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Brigadier-registered chat commands for mcpirates dev/debug.
 *
 * <h2>{@code /mcpirates fire on|off}</h2>
 *
 * <p>Runtime toggle for pirate-airship cannon firing
 * ({@link AirshipBrain#CANNON_FIRE_ENABLED}). Aiming is always on so the cannon
 * visually tracks the player; this toggle just controls whether projectiles are
 * actually fired. Defaults to OFF so test sessions don't get interrupted by
 * cannonballs from background pursuit ticks.
 *
 * <p>Requires permission level 2 (op) — same as vanilla cheats. In dev `Dev` is op
 * by default. The state is process-wide (one shared boolean across all pirate
 * airships), so toggling affects every live ship at once.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class MCPCommands {

    private MCPCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mcpirates")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("fire")
                        .then(Commands.literal("on").executes(ctx -> setFire(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> setFire(ctx.getSource(), false))));
        event.getDispatcher().register(root);
    }

    private static int setFire(CommandSourceStack source, boolean enabled) {
        AirshipBrain.setFireEnabled(enabled);
        Component msg = Component.literal(
                "mcpirates cannon fire is now " + (enabled ? "ON" : "OFF"));
        source.sendSuccess(() -> msg, /*broadcastToAdmins=*/true);
        return Command.SINGLE_SUCCESS;
    }
}
