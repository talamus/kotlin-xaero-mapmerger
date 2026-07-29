package mapmerger

/**
 * In-memory model of a region.xaero file (Xaero save format 6.8).
 *
 * The model is deliberately shallow: pixel/overlay parameter ints and block-state
 * NBT are kept as raw bytes and written back verbatim. Only the palette-related
 * bits (state/biome "new entry" flags) are recomputed on write, because the
 * region-wide palettes must be rebuilt after tiles from two files are mixed.
 */

/** Opaque byte blob (e.g. a block-state NBT compound), compared by content. */
class Blob(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is Blob && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

/**
 * A biome palette entry: either a modified-UTF-8 string (length-prefixed, the
 * normal case in 6.8) or a legacy 4-byte int id. Raw bytes either way.
 */
class BiomeRef(val asInt: Boolean, val blob: Blob) {
    override fun equals(other: Any?): Boolean = other is BiomeRef && asInt == other.asInt && blob == other.blob
    override fun hashCode(): Int = 31 * blob.hashCode() + if (asInt) 1 else 0
}

/** One overlay (water/glass layer) of a pixel. [state] is null for plain water. */
class Overlay(val params: Int, val state: Blob?, val customColor: Int?)

/**
 * One map pixel (one block column). [state] is null for grass (implicit).
 * [heightByte] is only present in files where the height didn't fit the params
 * int (bit 6); [topHeight] only when it differs from height (bit 24).
 */
class Pixel(
    val params: Int,
    val state: Blob?,
    val heightByte: Int?,
    val topHeight: Int?,
    val overlays: List<Overlay>?,
    val biome: BiomeRef?,
)

/** A MapTile = one Minecraft chunk = 16x16 pixels, in file order (x outer, z inner). */
class Tile(
    val pixels: Array<Pixel>,
    val interpretationVersion: Int,
    val caveStart: Int,
    val caveDepth: Int,
)

/** A MapTileChunk = 4x4 tiles, in file order (x outer, z inner); null = absent tile. */
class TileChunk(val tiles: Array<Tile?>)

/** A region = up to 8x8 tile chunks, keyed by their coords byte (x shl 4 or z). */
class Region(val tileChunks: LinkedHashMap<Int, TileChunk>)
