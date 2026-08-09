# Known limitations

Deliberately recorded design gaps. Each entry states the failure mode, whether it
bites today or only later, and what fixing it would take.

---

## L1: dimension IDs are derived by path matching, and break with mods

**Severity:** latent today, **data-destroying at Phase 2**
**Location:** `RegionBasedStorageMixin.rocksmc$dimensionId(File)`
**Status:** ✅ **RESOLVED** (Phase 1d, commit `7402825`)

> **Resolution.** `DimensionKey` now inverts vanilla's
> `DimensionType.getSaveDirectory` with a single anchored regex, recognising all
> four layouts including `dimensions/<namespace>/<path>`, and rejecting anything it
> does not understand rather than defaulting to the overworld. `DimensionRegistry`
> assigns each identity a stable ordinal persisted in a `dimensions` column family,
> with vanilla dimensions pinned to 0/1/2.
>
> The registry key would have been the semantically correct input but is not
> reachable from `RegionBasedStorage`; the alternatives (a `ThreadLocal` set in an
> outer constructor, or an `@Redirect` on the construction site) both depend on when
> and where code runs, which is exactly what other mods change. Deriving from the
> directory depends on nothing but its own argument.
>
> 31 unit tests cover every layout, both storage leaves, the substring
> misidentification the old code was prone to, and ordinal stability across reopen.
> Real-world fidelity is unchanged at 293,207/293,207.
>
> One inherent ambiguity is documented and deliberately resolved in favour of
> failing: a world whose *ancestor* path contains a `dimensions` segment is
> structurally indistinguishable from a malformed custom dimension, so it is
> rejected rather than guessed at.

The original analysis follows, retained because the reasoning about why path
matching was unsafe still explains the shape of the fix.

### The problem

Chunk keys are `dimension(4B) | morton(x,z)(8B)`. The dimension component is
derived by substring-matching the save directory:

```java
if (path.contains("/DIM-1")) return -1;   // nether
if (path.contains("/DIM1"))  return 1;    // end
return 0;                                 // "overworld"
```

That covers exactly the three vanilla dimensions. Vanilla's own directory logic
(`DimensionType.getSaveDirectory`) shows why it is insufficient:

| Dimension | Save directory |
|---|---|
| Overworld | `<world>/` |
| Nether | `<world>/DIM-1/` |
| End | `<world>/DIM1/` |
| **Anything else** | **`<world>/dimensions/<namespace>/<path>/`** |

Custom dimensions — from datapacks or mods — land in that fourth row, match
neither prefix, and **fall through to the overworld's ID of 0**:

| Save directory | Derived ID |
|---|---|
| `world/region` | 0 |
| `world/DIM-1/region` | −1 |
| `world/DIM1/region` | 1 |
| `world/dimensions/twilightforest/twilight_forest/region` | **0** |
| `world/dimensions/aether/the_aether/region` | **0** |
| `world/dimensions/mininglands/mining/region` | **0** |

Every custom dimension collides with the Overworld and with every other custom
dimension.

### Why it does not bite yet

`RegionBasedStorageMixin` currently opens **one database per storage directory**:

```java
File dbPath = new File(directory.getParentFile(), directory.getName() + ".rocksdb");
```

So `world/region` and `world/dimensions/aether/the_aether/region` get separate
databases (`region.rocksdb` and `the_aether/region.rocksdb`). The keys collide,
but they live in different keyspaces, so nothing is lost. The dimension component
is currently redundant.

### Why it becomes destructive at Phase 2

Phase 2 merges chunk and POI storage into **one database with column families** —
that is the whole point, since it is what enables atomic cross-subsystem commits.
The moment stores share a keyspace, colliding keys stop being harmless:

```
id=-1: world/DIM-1/region
id= 0: world/region, world/dimensions/twilightforest/…, world/dimensions/aether/…
       ^^^^ three dimensions, one keyspace: silent overwrite
id= 1: world/DIM1/region
```

Chunk (0, 0) in the Overworld and chunk (0, 0) in a modded dimension would map to
an identical key. One would silently overwrite the other. **Terrain loss with no
error, no warning, and no way to recover the overwritten side.**

### Secondary problem: the check is a substring match

`path.contains("/DIM1")` also matches any path containing that sequence — for
example a world saved in a directory named `.../DIM1-backup/`. Unlikely, but the
predicate is wrong in kind, not merely incomplete.

### What a fix requires

The path is the wrong input. The dimension identity is already available as a
`RegistryKey<World>`, which carries a namespaced ID (`minecraft:overworld`,
`twilightforest:twilight_forest`). Two viable approaches:

1. **Plumb the registry key through.** `RegionBasedStorage` never sees it —
   `ThreadedAnvilChunkStorage` does (`this.saveDir = session.getWorldDirectory(
   serverWorld.getRegistryKey())`). Capturing it there and associating it with the
   storage instance is the correct fix, but requires a second mixin and a way to
   correlate the two.

2. **Persist a dimension registry inside the database.** Map the namespaced ID
   string to a stable integer on first use, store that mapping in a metadata
   column family, and reuse it thereafter. Handles arbitrary dimensions, survives
   restarts, and needs no extra mixin — but adds a lookup and its own migration
   concern if the mapping is ever lost.

Approach 2 is likely better: it keeps the seam narrow, and the mapping is
naturally covered by checkpoints and backups along with everything else.

Whichever is chosen, **it must land before Phase 2**, and the fix needs a
migration path for any world already written with path-derived IDs.

### Interim mitigation

Until fixed, the mod should refuse to start when custom dimensions are present
rather than write data it cannot correctly address later. Failing loudly is
consistent with the existing decision not to silently fall back to Anvil on
database-open failure: a half-addressed world that looks healthy is worse than a
server that will not boot.

Not yet implemented.

---

## L2: separate databases cannot recover to a coherent cross-dimension state

**Severity:** **corrupting on crash** — can duplicate or destroy entities
**Location:** `RegionBasedStorageMixin` opens one database per storage directory
**Status:** open; fixed by Phase 2 (see [`../TODO.md`](../TODO.md)), which is
agreed to land **before** the beta

### The problem

`MinecraftServer.save()` iterates worlds **sequentially** — Overworld, then Nether,
then End — calling `serverWorld.save(...)` on each independently.

Each `(dimension, leaf)` currently gets its own RocksDB, so each has its own
write-ahead log and its own group-commit boundary. A crash part-way through an
autosave therefore recovers every database to a *different* point in that sequence.

Minecraft runs a single tick loop for all dimensions, so there is no tick at which
the Overworld had finished saving but the Nether had not. **Recovery lands on a
state that no tick ever produced.**

### Why that is worse than losing a few seconds

Cross-dimension state exists, and a torn recovery splits it:

| Coupling | Torn outcome |
|---|---|
| Entity teleport between dimensions (`Entity.moveToWorld`) | The entity is removed from the source and added to the destination. Two commit boundaries mean it can end up in **both** worlds — a duplication bug created by crash recovery — or in **neither** |
| Nether portal linkage | A paired portal survives on one side only |
| Map item data | All map state routes through the *Overworld's* `PersistentStateManager` regardless of which dimension the map depicts, so a Nether map already depends on Overworld storage |

Entity duplication is the worst of these: it is an item and mob dupe that appears
without any player action, and it would be indistinguishable from an exploit.

### Secondary consequence: resources multiply

Because each store constructs its own options, a three-dimension world opens six
stores and multiplies every memory setting by six — block cache, memtables and
background threads alike. `docs/beta-setup.md` documents per-store values that add
up to the intended totals as an interim measure.

### What the fix does and does not give

One database per world gives **one WAL and therefore one recovery point** across all
dimensions, which removes this failure class entirely.

It does **not** by itself make chunk and POI writes atomic with respect to each
other: RocksDB guarantees atomicity per `WriteBatch`, and vanilla's writes originate
in independent `StorageIoWorker` instances above this mod's seam. That remains
follow-up work and should not be claimed on the strength of consolidation alone.
