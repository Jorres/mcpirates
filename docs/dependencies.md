# Dependencies

How each upstream is wired in, and where to get the artifact.

## Maven repositories

| Repo                                   | Provides                          |
|----------------------------------------|-----------------------------------|
| `https://maven.createmod.net`          | Create, Ponder, Flywheel          |
| `https://mvn.devos.one/snapshots`      | Registrate                        |
| `https://maven.ryanhcode.dev/releases` | Sable                             |
| `https://api.modrinth.com/maven`       | (fallback, currently unused)      |
| `flatDir libs/`                        | Create Aeronautics (manual drop)  |

## Versions

Pinned in `gradle.properties`. Matched to the versions Aeronautics 1.2.1 itself was built
against, so the runtime classpath is internally consistent.

| Dep         | Coordinate                                                              |
|-------------|-------------------------------------------------------------------------|
| Create      | `com.simibubi.create:create-1.21.1:6.0.10-280`                          |
| Ponder      | `net.createmod.ponder:ponder-neoforge:1.0.81+mc1.21.1`                  |
| Flywheel    | `dev.engine-room.flywheel:flywheel-neoforge-1.21.1:1.0.6` (+ -api)      |
| Registrate  | `com.tterrag.registrate:Registrate:MC1.21-1.3.0+67`                     |
| Sable       | `dev.ryanhcode.sable:sable-neoforge-1.21.1:1.2.1`                       |
| Aeronautics | `libs/create-aeronautics-bundled-*.jar` (see below)                     |

## Getting the Aeronautics jar

Aeronautics has no public dev maven. Three options, easiest first:

### Option 1 — download the released bundled jar

1. Get `create-aeronautics-bundled-1.21.1-<version>.jar` from
   [Modrinth](https://modrinth.com/mod/create-aeronautics) or CurseForge.
2. Drop it into `libs/`.
3. `./gradlew build` — the `flatDir` repo + `fileTree('libs')` dep picks it up.

The bundled jar already contains Sable, simulated, and offroad submodules, so dropping it in
also satisfies those at runtime. Compile-time, we still pull Sable from the public maven so
that classes are unobfuscated.

### Option 2 — build it locally from the cloned source

```
cd sources/Simulated-Project
./gradlew :aeronautics-bundled:build
cp aeronautics-bundled/build/libs/create-aeronautics-bundled-1.21.1-*.jar ../../libs/
```

This needs Java 21 and ~5 GB free. First sync is long (NeoForm decompile).

### Option 3 — composite build (NOT currently configured)

Add `includeBuild '../sources/Simulated-Project'` to `settings.gradle` and depend on
`dev.eriksonn.aeronautics:aeronautics-neoforge`. We rejected this — see `decisions.md`.

## NeoForge 219 → 228 note

Aeronautics 1.2.1 is compiled against NeoForge `21.1.219`. We build/run against `21.1.228`. The
21.1.x range is patch-level — same MC, no API breaks documented in the NeoForge changelog
between those two — so the bundled Aeronautics jar should load against the newer loader without
recompilation. **If something breaks, first thing to try is dropping our `neoforge_version` to
`21.1.219` in `gradle.properties`.**

## Sable companion

`dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:1.6.0` is a `compileOnly` dep of
Aeronautics itself. We don't need it directly unless we wrap Aeronautics' Sable-companion
integration; if so, add it as a `compileOnly` in `build.gradle`.
