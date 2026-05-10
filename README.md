# MC Pirates

NeoForge addon for **Create Aeronautics** that puts airships at pillager outposts. When a player
approaches, the airship lifts off and engages.

> **Status:** skeleton — no gameplay code yet, just plumbing.

## Versions (pinned)

| Component   | Version              |
|-------------|----------------------|
| Minecraft   | 1.21.1               |
| NeoForge    | 21.1.228             |
| Java        | 21                   |
| Parchment   | 1.21 / 2024.11.10    |
| Create      | 6.0.10-280           |
| Sable       | 1.2.1                |
| Flywheel    | 1.0.6                |
| Ponder      | 1.0.81               |
| Registrate  | MC1.21-1.3.0+67      |
| Aeronautics | 1.2.1 (target)       |

See [docs/dependencies.md](docs/dependencies.md) for how to obtain the Aeronautics jar (no public
dev maven yet) and notes on the NeoForge 219 → 228 gap.

## Layout

```
mcpirates/
├── build.gradle, settings.gradle, gradle.properties   # NeoForge moddev project
├── gradlew, gradlew.bat, gradle/wrapper/              # Gradle 9.4.1
├── libs/                                              # drop Create-Aeronautics-bundled jar here
├── src/main/java/com/mcpirates/                       # mod sources
├── src/main/resources/                                # static resources (pack.mcmeta, lang)
├── src/main/templates/META-INF/neoforge.mods.toml     # processed at build time
├── docs/                                              # design notes, decisions, deps
└── sources/                                           # cloned upstream repos (gitignored)
    ├── Create/
    ├── Simulated-Project/   (Aeronautics + simulated + offroad)
    └── sable/
```

## Quick start

1. Drop the Create Aeronautics bundled jar into `libs/` (see `docs/dependencies.md`).
2. `./gradlew build` — produces a NeoForge mod jar in `build/libs/`.
3. `./gradlew runClient` — boots a dev client with the mod loaded.
4. `./gradlew runData` — runs the data generators.

## Docs

- [Design](docs/design.md) — what the mod does, how it hooks into Aeronautics/Sable.
- [Decisions](docs/decisions.md) — running log of architectural decisions.
- [Dependencies](docs/dependencies.md) — how each upstream is wired in.
