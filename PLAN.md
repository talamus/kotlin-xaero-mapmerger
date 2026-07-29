#  Plan and File Format

Merge two Xaero's World Map save folders into one. Kotlin/JVM, distributed as a
runnable jar:

```bash
java -jar XaeroMapmerger.jar <top world-map server folder> <bottom world-map server folder> <output folder>
```

(A `--darken` feature was considered and dropped: the old tool's colour trick does
not exist in format 6.8, and emulating it via overlays adds complexity for no real
benefit.)

## 1. What we learned from the materials

### 1.1 The format we must handle: `region.xaero` version 6.8

Our target client (`xaeroworldmap-fabric-1.20.1-1.44.2.jar`, MC 1.20.1) writes save
format **major 6, minor 8**. Verified against the example data: every region zip starts
with bytes `FF 00 06 00 08` (`0xFF` marker, then `(major << 16) | minor` as a big-endian
int = `0x00060008`).

Three independent references agree on the byte-level layout:

* `materials/XaerosMapFormat_c_plusplus_library/src/RegionTools.cpp` — complete
  parser for all versions up to 7.8 and a writer (`parseRegion` / `serializeRegionImpl`).
* `materials/MapSyncer_from_server_to_client/docs/design/region-xaero-format.md` —
  written spec of exactly version 6.8 (based on decompiled Xaero code).
* `materials/MapSyncer_from_server_to_client/libs/core/.../RegionConverterStandalone.java` —
  a pure-Java **writer** of 6.8 files that real 1.20.1 clients accept in production.
  This is our closest template (same JVM, same `DataOutputStream` semantics).

Structure summary (all integers big-endian, Java `DataInput`/`DataOutput` semantics):

```
file  = 0xFF, int version(0x00060008), then a sequence of TileChunks until EOF
TileChunk = byte coords (x << 4 | z, each 0-7), then 4x4 MapTiles in fixed order
MapTile   = int -1 if absent; otherwise 16x16 pixels (x outer, z inner), then
            byte interpretationVersion, int caveStart, byte caveDepth
Pixel     = int params bitfield:
              bit 0     not-grass (block state follows)
              bit 1     has overlays
              bits 2-3  legacy colour type (always 0 in 6.8)
              bit 6     height NOT in params (height byte follows pixel data)
              bits 8-11 light
              bits 12-19 height low 8 bits
              bit 20    has biome
              bit 21    block state is new palette entry
              bit 22    biome is new palette entry
              bit 23    biome as int (legacy)
              bit 24    topHeight differs from height (extra byte follows)
              bits 25-28 height high 4 bits (12-bit signed total)
            then, in order, conditionally:
              block state: NBT compound (new palette entry) or int palette index
              topHeight byte
              overlay count byte + overlays
              biome: writeUTF string (new palette entry) or int palette index
Overlay   = int params bitfield:
              bit 0 not-water, bits 4-7 light, bit 10 state is new palette entry,
              bits 11-14 opacity
            then block state NBT or int palette index (shares the pixel state palette)
```

**Key property that dictates the whole design:** block-state and biome palettes are
*region-wide and built incrementally during the write* — the first occurrence carries
the full NBT/string inline, later occurrences are just indices. Therefore the old
tool's trick of splicing raw chunk bytes from two files is impossible: an index from
file B is meaningless in file A's palette. We must **fully parse both regions into an
object model, merge, and re-serialize with freshly built palettes.**

We do *not* need to understand the block-state NBT semantically. We only need to know
where each compound ends. We can treat states as opaque byte blobs (parse to skip,
compare by content for palette dedup, write back verbatim). No lookup tables, no
block/biome registries, no dependency on the C++ library's data files.

### 1.2 On-disk layout (from the example data)

```
<world-map>/Multiplayer_<server>/
├── server_config.txt
└── <dim>/                        # null = overworld, DIM-1 = nether, DIM1 = end
    ├── dimension_config.txt      # includes MWName lines naming the multiworlds
    └── mw$<id>/                  # one folder per multiworld
        ├── X_Z.zip               # region: zip with single entry "region.xaero"
        ├── caves/<layer>/X_Z.zip # cave-mode layers, same region format
        ├── cache_1/*.xwmc, cache/N/*  # rendered caches — regenerable, SKIP
        ├── *.xwmc.outdated       # stale cache markers — SKIP
        └── .lock                 # runtime lock — SKIP
```

Facts about our example data:
* Multiworld IDs are stable across clients: both Tero and Anne have
  `null/mw$1109783965`. Tero additionally has `mw$-2075292788` ("Map 1") and
  `mw$-1679386150` ("Map 3") which only exist on his side.
* Real overlap to deep-merge: `null/mw$1109783965` (98 vs 52 surface regions, plus
  many shared cave layers). Everything else is copy-through.
* `.xwmc` caches are derived data. MapSyncer ships only region zips and the client
  re-renders; `.outdated` markers exist only next to caches. We will not copy caches —
  the client rebuilds them on first view. **Confirmed with a real client: caches
  deleted, everything worked fine.**

## 2. Merge semantics

Mirrors the old tool, adapted to the new directory tree:

1. Inputs are two `Multiplayer_<server>` folders: **top** (first arg, wins conflicts)
   and **bottom** (second arg).
2. Enumerate *map units* = relative paths `dim/mw$id` and `dim/mw$id/caves/layer`
   in either input. For each unit, enumerate region zips `X_Z.zip`.
3. Per region file:
   * only in one input → copy as-is.
   * in both → parse both; merge at **MapTile granularity** (16x16-pixel MC chunk,
     same unit the old tool used): tile present in top wins, otherwise take bottom's
     tile. Re-serialize as 6.8.
4. Copy `server_config.txt` and each `dimension_config.txt` from top (union the
   bottom's `MWName:` lines for multiworlds that exist only there).
5. Skip caches, `.outdated`, `.lock` entirely.
6. Output folder is created; refuse to run if it already exists and is non-empty
   (protects against writing into a live client folder).

Not in scope for v1 (note for later): cross-ID multiworld merging (e.g. treating
Tero's "Map 1" and "Map 2" as the same world) — would need an explicit mapping flag.

## 3. Implementation steps

### Step 1 — Project scaffolding
Plain `kotlinc` build (no Gradle — it isn't installed, and the tool has **zero
runtime deps** beyond the Kotlin stdlib: zip via `java.util.zip`, IO via
`DataInputStream`/`DataOutputStream`). `build.sh` runs
`kotlinc src/main/kotlin -include-runtime -d XaeroMapmerger.jar`, which bundles the
stdlib and sets the `Main-Class` manifest. Validation runs as a built-in
`--selftest` mode instead of a separate JUnit setup.

### Step 2 — Data model + binary reader (`RegionReader`)
* Model: `Region` → 8x8 `TileChunk?` → 4x4 `MapTile?` → 16x16 `Pixel`
  (light, height, topHeight?, stateBlob?, biome?, overlays), plus per-tile
  `interpretationVersion`, `caveStart`, `caveDepth`.
* `BlockStateBlob`: raw NBT bytes + content-based equals/hashCode. Includes a minimal
  NBT compound *skipper* (walk tag types to find the end — ~60 lines, no semantics).
* Reader accepts exactly version 6.8 and fails with a clear message otherwise
  (both our inputs are 6.8; older majors need biome-ID and state-ID conversion
  tables we deliberately avoid).

### Step 3 — Binary writer (`RegionWriter`)
Serialize the model back to 6.8, rebuilding both palettes incrementally in write
order, zip up as single-entry `region.xaero`, `X_Z.zip`.

### Step 4 — Round-trip validation harness (the safety net)
Test that for **all ~470 region zips in the example data**: parse → serialize →
byte-compare with the original payload. Xaero writes tiles/pixels in fixed order, so
palettes rebuild identically and byte-identity should hold; if an edge case differs,
fall back to parse → serialize → parse → model-equality for that case and investigate.
This single test proves reader and writer against every real-world quirk we own.

### Step 5 — Merge engine
Tile-level merge as per §2. Parallelize per region file with a fixed thread pool
(regions are independent).

### Step 6 — CLI
Positional args: top, bottom, out. Flags: `--threads N`. Print per-unit stats
(copied / merged counts).

### Step 7 — End-to-end verification
1. Run on the example data; verify output structure, region counts, and that every
   output zip re-parses.
2. Load the output in a real client (drop into a test profile's
   `xaero/world-map/`) — verify rendering. This is the only step needing a human
   with a Minecraft client.

## 4. Risks / open questions

| Risk | Mitigation |
|------|------------|
| Byte-identical round-trip may fail on some edge pixel | fall back to model-equality; investigate before merging |
| Client behaviour with missing caches | Tested with a real client: caches deleted, everything worked fine |
| Multiworld pairing by identical `mw$id` | holds in example data; cross-ID mapping deferred |
