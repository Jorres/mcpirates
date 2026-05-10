# Design — MC Pirates

## Goal

Pillager outposts spawn an airship parked nearby. When a player gets close, the airship lifts
off, and some "air combat" plays out (pillagers shoot crossbows at the player, the airship
maneuvers, etc.). No procedural generation — a small set of premade ship designs.

## High-level architecture

```
World gen / outpost hook
        │
        ▼
Airship template loader  ─── reads premade NBT structures (one per ship design)
        │
        ▼
Spawn manager  ─── places template blocks at the outpost on first chunk load
        │
        ▼
Proximity trigger  ─── detects player within radius, lifts ship via Aeronautics contraption
        │
        ▼
AI / combat behavior  ─── pillagers spawned aboard, ship moves with simple waypoints
```

## Open questions

1. **How do we lift a static block structure into a moving Aeronautics contraption at runtime?**
   Aeronautics builds contraptions from assembled block clusters; we need to figure out the
   right entry point. Candidates to investigate in the cloned source:
   - `dev.eriksonn.aeronautics.contraption.*` (look for assembly / take-off logic)
   - Sable physics-structure entry: `dev.ryanhcode.sable.*` for the moving body itself
   - Create's `Contraption.assemble()` flow as a reference pattern.

2. **Where do we hook outpost spawning?**
   Two options:
   - Add a structure piece to the vanilla `pillager_outpost` template pool via datapack.
   - Listen on `ServerLevel` chunk-load / structure-start events and post-process.
   The datapack approach is more idiomatic but constrains us to placing the *parked* ship as
   blocks; the post-process approach gives more control over what becomes a contraption vs.
   what stays as scenery.

3. **Premade ship designs** will live in `data/mcpirates/structures/airships/*.nbt` (vanilla
   structure NBT format). Plan: 2–3 designs to start (small scout, medium gunship).

4. **AI**: keep it dumb. Pillagers riding the ship use vanilla AI to shoot. The ship itself
   just translates toward the player at low speed and rotates to keep broadside facing them.

## Non-goals (for v0.1)

- No procedural ship generation
- No multiplayer-tuned netcode beyond what Aeronautics already provides
- No new pillager mob types
- No loot tables on the ship (yet)
