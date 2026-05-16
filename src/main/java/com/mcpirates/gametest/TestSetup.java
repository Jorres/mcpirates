package com.mcpirates.gametest;

import com.mcpirates.MCPirates;
import com.mcpirates.airship.AirshipBrain;
import com.mcpirates.airship.AirshipLiftoffTrigger;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-test reset. Wipes JVM-static registries (brain ships, ground engagements,
 * targetOverride) and removes any SubLevels still in the parent container from
 * a prior test.
 *
 * <p>Cross-arena interference is handled by {@code StructureGridSpawnerMixin},
 * which widens the gap between test arenas past
 * {@code AirshipLiftoffTrigger}'s 160-block trigger radius — so no test's
 * production proximity scan reaches into a neighbour arena. This reset only
 * needs to clear the in-JVM state that lives outside the level.
 *
 * <p>SubLevel removal: Sable's {@code GameTestInfoMixin.succeed} cleans
 * SubLevels intersecting the test bbox, but airships that flew past the arena
 * ceiling escape it. We re-sweep here for completeness.
 */
public final class TestSetup {
    private TestSetup() {}

    public static void reset(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AirshipBrain.unregisterAll(level);
        AirshipLiftoffTrigger.clearGroundEngagements(level);
        AirshipBrain.targetOverride = null;
        AirshipBrain.targetShipOverride = null;
        purgeSubLevels(level);
    }

    private static void purgeSubLevels(ServerLevel level) {
        ServerSubLevelContainer container =
                (ServerSubLevelContainer) SubLevelContainer.getContainer(level);
        if (container == null) return;
        List<SubLevel> stale = new ArrayList<>(container.getAllSubLevels());
        if (stale.isEmpty()) return;
        MCPirates.LOGGER.info("[gametest-reset] purging {} leftover SubLevel(s)", stale.size());
        for (SubLevel sl : stale) {
            container.removeSubLevel(sl, SubLevelRemovalReason.REMOVED);
        }
    }
}
