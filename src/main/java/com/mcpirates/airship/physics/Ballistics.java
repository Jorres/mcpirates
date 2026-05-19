package com.mcpirates.airship.physics;

/**
 * World-frame ballistic solver for Create: Big Cannons solid shot.
 *
 * <p>Trajectory model matches CBC's {@code AbstractCannonProjectile.getForces}: per tick,
 * {@code v -= DRAG * |v| * v̂ + (0, GRAVITY, 0)}. Defaults reflect the {@code solid_shot.json}
 * + overworld {@code gravity_multiplier=1.0} configuration; if either is overridden, retune.
 *
 * <p>Pure math — no Minecraft / Sable imports, unit-testable in isolation.
 */
public final class Ballistics {

    static final double GRAVITY = 0.05;
    static final double DRAG    = 0.01;

    private static final int SOLVER_ITERATIONS = 20;
    private static final int MAX_TICKS = 200;

    private Ballistics() {}

    /** Low-arc pitch (radians) that hits ({@code horizDist}, {@code dy}) from origin at
     *  muzzle speed {@code v0} (blocks/tick). Returns {@link Double#NaN} when no pitch
     *  in [-π/2, π/4] reaches the target. */
    public static double solvePitch(double horizDist, double dy, double v0) {
        if (horizDist <= 0) return Double.NaN;
        double lo = -Math.PI / 2;
        double hi =  Math.PI / 4;
        if (simulateY(horizDist, v0, hi) < dy) return Double.NaN;
        for (int i = 0; i < SOLVER_ITERATIONS; i++) {
            double mid = (lo + hi) / 2;
            if (simulateY(horizDist, v0, mid) > dy) hi = mid; else lo = mid;
        }
        return (lo + hi) / 2;
    }

    /** Y-coordinate at the first tick where horizontal travel ≥ {@code horizDist}
     *  (linearly interpolated). Returns a large negative sentinel if the shot falls short
     *  inside {@link #MAX_TICKS}. */
    private static double simulateY(double horizDist, double v0, double pitch) {
        double x = 0, y = 0;
        double vx = v0 * Math.cos(pitch);
        double vy = v0 * Math.sin(pitch);
        for (int t = 0; t < MAX_TICKS; t++) {
            double prevX = x, prevY = y;
            double v = Math.sqrt(vx * vx + vy * vy);
            if (v > 0) {
                double dragAccel = DRAG * v;
                vx -= dragAccel * (vx / v);
                vy -= dragAccel * (vy / v);
            }
            vy -= GRAVITY;
            x += vx;
            y += vy;
            if (x >= horizDist) {
                double s = (horizDist - prevX) / (x - prevX);
                return prevY + s * (y - prevY);
            }
        }
        return -1e9;
    }
}
