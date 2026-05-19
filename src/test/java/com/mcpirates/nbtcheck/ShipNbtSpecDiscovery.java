package com.mcpirates.nbtcheck;

import com.mcpirates.airship.ships.ShipNbtSpec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reflection-based discovery of every {@link ShipNbtSpec} in the build classpath. Used
 * by nbtcheck tests so adding a new ship requires no central registry edit — just drop
 * a {@code <Name>NbtSpec} class in the ship folder implementing the interface, with
 * {@code public static final <Name>NbtSpec INSTANCE = new <Name>NbtSpec();}.
 *
 * <p>Walks {@code build/classes/java/main/com/mcpirates/airship/ships/} for any
 * {@code *NbtSpec.class}, loads it, and reads its {@code INSTANCE} field. NbtSpec
 * classes are pure-Java by convention, so {@code Class.forName} won't pull in
 * Minecraft classes (which would fail the lightweight nbtcheck test JVM).
 */
final class ShipNbtSpecDiscovery {

    private static final Path CLASSES_ROOT = Path.of("build/classes/java/main");
    private static final Path SHIPS_PKG = CLASSES_ROOT.resolve("com/mcpirates/airship/ships");

    private ShipNbtSpecDiscovery() {}

    static List<ShipNbtSpec> all() {
        if (!Files.isDirectory(SHIPS_PKG)) {
            throw new IllegalStateException("ships classes not built at " + SHIPS_PKG
                    + " — run `gradlew compileJava` first");
        }
        List<ShipNbtSpec> specs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(SHIPS_PKG)) {
            walk.filter(p -> {
                        String name = p.getFileName().toString();
                        // Skip the interface itself; only collect implementations.
                        return name.endsWith("NbtSpec.class") && !name.equals("ShipNbtSpec.class");
                    })
                    .forEach(p -> specs.add(load(p)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return specs;
    }

    private static ShipNbtSpec load(Path classFile) {
        String relative = CLASSES_ROOT.relativize(classFile).toString().replace('\\', '/');
        String fqn = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
        try {
            Class<?> cls = Class.forName(fqn);
            if (!ShipNbtSpec.class.isAssignableFrom(cls)) {
                throw new IllegalStateException(fqn + " doesn't implement ShipNbtSpec");
            }
            Object instance = cls.getField("INSTANCE").get(null);
            return (ShipNbtSpec) instance;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("failed to load spec " + fqn
                    + " — needs `public static final <Name>NbtSpec INSTANCE = new <Name>NbtSpec();`", e);
        }
    }
}
