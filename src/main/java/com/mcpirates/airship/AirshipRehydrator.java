package com.mcpirates.airship;

import com.mcpirates.MCPirates;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Re-register persisted SubLevels with the brain on restart / chunk reload. Reads
 * everything from {@code userDataTag.mcpirates} written by {@link Airship#persist()};
 * no world rescan. Fresh assemblies register directly via {@link AirshipLiftoffTrigger};
 * the duplicate-skip below bails when the trigger beat the observer.
 */
@EventBusSubscriber(modid = MCPirates.MOD_ID)
public final class AirshipRehydrator {

    private AirshipRehydrator() {}

    /** Queued because {@code onSubLevelAdded} fires before {@code setUserDataTag};
     *  {@link #onServerTick} retries once the tag is in place. */
    private static final java.util.Set<UUID> PENDING = ConcurrentHashMap.newKeySet();

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        var server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            for (SubLevel sl : container.getAllSubLevels()) {
                if (!sl.isRemoved()) tryRehydrate(level, sl);
            }
            container.addObserver(new SubLevelObserver() {
                @Override public void onSubLevelAdded(SubLevel subLevel) {
                    PENDING.add(subLevel.getUniqueId());
                }
                @Override public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
                    PENDING.remove(subLevel.getUniqueId());
                }
            });
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            for (UUID uuid : PENDING) {
                SubLevel sl = container.getSubLevel(uuid);
                if (sl == null || sl.isRemoved()) {
                    PENDING.remove(uuid);
                    continue;
                }
                if (!(sl instanceof ServerSubLevel ssl)) {
                    PENDING.remove(uuid);
                    continue;
                }
                if (ssl.getUserDataTag() == null) continue; // still loading — retry next tick
                PENDING.remove(uuid);
                tryRehydrate(level, sl);
            }
        }
    }

    /** Public for GameTests. */
    public static int rehydrateLevel(ServerLevel level) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return 0;
        int registered = 0;
        for (SubLevel sl : container.getAllSubLevels()) {
            if (sl.isRemoved()) continue;
            if (tryRehydrate(level, sl)) registered++;
        }
        return registered;
    }

    private static boolean tryRehydrate(ServerLevel parentLevel, SubLevel subLevel) {
        if (!(subLevel instanceof ServerSubLevel ssl)) return false;
        for (Airship existing : AirshipBrain.ships()) {
            if (existing.subLevelId.equals(ssl.getUniqueId())) return false;
        }
        CompoundTag userTag = ssl.getUserDataTag();
        if (userTag == null || !userTag.contains(Airship.NBT_ROOT_KEY)) return false;
        Airship airship = Airship.readNbt(parentLevel, ssl, userTag.getCompound(Airship.NBT_ROOT_KEY));
        if (airship == null) {
            MCPirates.LOGGER.warn("rehydrate: failed to deserialize SubLevel {}", ssl.getUniqueId());
            return false;
        }
        AirshipBrain.registerRehydrated(airship);
        return true;
    }
}
