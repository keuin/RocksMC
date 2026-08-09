# Known limitations

Deliberately recorded design gaps. Each entry states the failure mode, whether it
bites today or only later, and what fixing it would take.

---

## L1: dimension IDs are derived by path matching, and break with mods

**Severity:** latent today, **data-destroying at Phase 2**
**Location:** `RegionBasedStorageMixin.rocksmc$dimensionId(File)`
**Status:** open

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
