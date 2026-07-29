# XaeroMapmerger

Merges two Xaero's World Map save folders into one, so map data explored by two
players can be combined. Targets save format **6.8** as written by
_Xaero's World Map 1.44.2_ for _Minecraft 1.20.1_.

This is a complete re-write of [@XaeroRegionMerger.java](https://github.com/Entropy5/JMtoXaero/blob/Region-Scripts/src/main/java/com/github/entropy5/XaeroRegionMerger.java)
by [@Entropy5](https://github.com/Entropy5) that relies on an older version of 
the Xaero World Map format.

## Build

Requires JDK 21 and `kotlinc` (both installable via sdkman). No other dependencies.

```bash
./build.sh    # produces self-contained XaeroMapmerger.jar
```

## Usage

```bash
java -jar XaeroMapmerger.jar <top folder> <bottom folder> <output folder> [--threads N]
```

The folders are `Multiplayer_<server>` directories from a client's
`xaero/world-map/`. Example:

```bash
java -jar XaeroMapmerger.jar \
    Client_1/minecraft/xaero/world-map/Multiplayer_some.minecraft.server \
    Client_2/minecraft/xaero/world-map/Multiplayer_some.minecraft.server \
    Combined/Multiplayer_some.minecraft.server
```

To use the result, move the output folder into a client's `xaero/world-map/`
(replacing or renaming the existing folder for that server).

### Rules

* Where both maps cover the same Minecraft chunk, the **top** (first) folder wins.
* All dimensions, multiworlds (`mw$…`) and cave layers are merged; regions present
  in only one input are copied bit-for-bit.
* `server_config.txt` / `dimension_config.txt` are carried over (multiworld name
  lists are unioned).
* Render caches (`.xwmc`), `.lock` and `.outdated` files are skipped — the client
  rebuilds caches automatically on first view.

### Self-test

```bash
java -jar XaeroMapmerger.jar --selftest <world-map folder>...
```

Parses and re-serializes every region zip found and verifies the output is
byte-identical to the original (nothing is written). Useful as a compatibility
check before merging maps from a new Xaero version: if the self-test passes on
your data, merging is safe. Fails loudly on any region version other than 6.8.

## Notes

* `PLAN.md` documents the region.xaero 6.8 format and the design.
* The merger treats block-state NBT and biome strings as opaque bytes; only the
  region-wide palettes are rebuilt when tiles from two files are combined, so
  merged data is written back exactly as the client wrote it.
