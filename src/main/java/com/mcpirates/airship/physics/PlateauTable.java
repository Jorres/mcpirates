package com.mcpirates.airship.physics;

import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Precomputed map "target altitude → (volume, lever) the ship will plateau near".
 * Built once per ship when the balloon first attaches and rebuilt only on balloon
 * capacity change.
 *
 * <p>At a given setting, equilibrium altitude {@code y_eq} satisfies
 * {@code pressure(y_eq) = mass / (T · liftStrength)} where {@code T} is total gas
 * output across all burners. The actuator space is small (volume in 5 m³ steps, lever
 * 1..15, identical across burners → ~hundreds of unique T values), so we enumerate
 * every reachable setting and root-find {@code y_eq} via bisection on Sable's pressure
 * curve. Lookup is then a binary search by altitude.
 *
 * <p>Mass is sampled at build time and never refreshed — block damage or cargo will
 * silently bias the lookup. The residual altitude error is small for small mass
 * deltas and tracking it cheaply would need a Sable mass-tracker hook we don't have.
 */
public final class PlateauTable {

    /** Aeronautics config default ({@code hot_air_burner_max}). */
    public static final int BURNER_MAX_VOLUME = 500;
    /** Hard-coded floor inside {@code HotAirBurnerBlockEntity}. */
    public static final int BURNER_MIN_VOLUME = 5;
    /** Wrench-UI quantisation step; writes off-step round on first wrench-open. */
    public static final int BURNER_VOLUME_STEP = 5;

    /** Burner actuator pair the brain writes each tick; gas output = {@code volume * lever / 15}. */
    public record LiftSetting(int lever, int volume) {}

    /** Snap a volume down to the nearest {@link #BURNER_VOLUME_STEP}, clamped to {@link #BURNER_MIN_VOLUME}. */
    public static int snapVolume(int v) {
        return Math.max(BURNER_MIN_VOLUME, (v / BURNER_VOLUME_STEP) * BURNER_VOLUME_STEP);
    }

    public record Row(int volume, int lever, double totalT, double equilibriumY) {
        public LiftSetting toLiftSetting() { return new LiftSetting(lever, volume); }
    }

    private final Row[] rowsByY;

    private PlateauTable(Row[] rowsByY) { this.rowsByY = rowsByY; }

    public int size() { return rowsByY.length; }
    public double minY() { return rowsByY.length == 0 ? Double.NaN : rowsByY[0].equilibriumY; }
    public double maxY() { return rowsByY.length == 0 ? Double.NaN : rowsByY[rowsByY.length - 1].equilibriumY; }

    /** {@code sampleX/sampleZ} matter only if a datapack introduces XY-coupled pressure
     *  (the default Sable curve depends on y alone). */
    public static PlateauTable build(
            ServerLevel level,
            double mass,
            double liftStrength,
            int nBurners,
            int vMaxPerBurner,
            double sampleX, double sampleZ) {
        int vMaxSnapped = snapVolume(vMaxPerBurner);

        // Dedupe by total T — many (v, l) pairs collapse to the same output.
        Map<Long, Row> uniqByT = new HashMap<>();

        double yLow = level.getMinBuildHeight();
        double yHigh = level.getMaxBuildHeight() - 1;
        Vector3d probe = new Vector3d();

        for (int v = BURNER_MIN_VOLUME; v <= vMaxSnapped; v += BURNER_VOLUME_STEP) {
            for (int l = 1; l <= 15; l++) {
                double tPerBurner = (double) v * l / 15.0;
                double tTotal = tPerBurner * nBurners;
                long key = Math.round(tTotal * 1000.0);
                if (uniqByT.containsKey(key)) continue;
                double targetPressure = mass / (tTotal * liftStrength);
                double yEq = findAltitude(level, probe, sampleX, sampleZ, targetPressure, yLow, yHigh);
                if (Double.isNaN(yEq)) continue;
                uniqByT.put(key, new Row(v, l, tTotal, yEq));
            }
        }

        Row[] rows = uniqByT.values().toArray(new Row[0]);
        Arrays.sort(rows, Comparator.comparingDouble(Row::equilibriumY));
        return new PlateauTable(rows);
    }

    /** Bisect for {@code y} where {@code pressure(y) ≈ targetPressure}. Clamps to
     *  bounds when the target is outside the reachable pressure range. */
    private static double findAltitude(
            ServerLevel level, Vector3d probe,
            double x, double z, double targetPressure,
            double yLow, double yHigh) {
        double pLow = DimensionPhysicsData.getAirPressure(level, probe.set(x, yLow, z));
        double pHigh = DimensionPhysicsData.getAirPressure(level, probe.set(x, yHigh, z));
        if (pLow <= pHigh) return Double.NaN; // pressure not monotonic the expected way — bail
        if (targetPressure >= pLow) return yLow;
        if (targetPressure <= pHigh) return yHigh;

        for (int i = 0; i < 40; i++) {
            double mid = (yLow + yHigh) * 0.5;
            double pMid = DimensionPhysicsData.getAirPressure(level, probe.set(x, mid, z));
            if (pMid > targetPressure) yLow = mid; else yHigh = mid;
            if (yHigh - yLow < 0.25) break;
        }
        return (yLow + yHigh) * 0.5;
    }

    /** Caller must ensure {@link #size()} > 0. */
    public Row pickClosest(double targetY) {
        int lo = 0, hi = rowsByY.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (rowsByY[mid].equilibriumY < targetY) lo = mid + 1;
            else hi = mid;
        }
        Row best = rowsByY[lo];
        if (lo > 0) {
            Row prev = rowsByY[lo - 1];
            if (Math.abs(prev.equilibriumY - targetY) < Math.abs(best.equilibriumY - targetY)) {
                return prev;
            }
        }
        return best;
    }
}
