package mapmerger

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class RegionFormatException(message: String) : Exception(message)

/** Big-endian reader over an in-memory buffer (matches Java DataInput semantics). */
class ByteReader(private val buf: ByteArray) {
    var pos = 0
        private set

    val atEnd get() = pos >= buf.size

    fun u8(): Int {
        need(1)
        return buf[pos++].toInt() and 0xFF
    }

    fun i32(): Int {
        need(4)
        val v = (buf[pos].toInt() and 0xFF shl 24) or
            (buf[pos + 1].toInt() and 0xFF shl 16) or
            (buf[pos + 2].toInt() and 0xFF shl 8) or
            (buf[pos + 3].toInt() and 0xFF)
        pos += 4
        return v
    }

    fun skip(n: Int) {
        need(n)
        pos += n
    }

    /** Skips a Java modified-UTF-8 string (unsigned short length + bytes). */
    fun skipUtf() = skip((u8() shl 8) or u8())

    /** Raw bytes from [from] up to the current position. */
    fun sliceFrom(from: Int) = Blob(buf.copyOfRange(from, pos))

    private fun need(n: Int) {
        if (pos + n > buf.size) {
            throw RegionFormatException("unexpected end of data at offset $pos (needed $n more bytes)")
        }
    }
}

/**
 * Reader/writer for region.xaero version 6.8 — the format written by Xaero's
 * World Map 1.44.2 for Minecraft 1.20.1. See PLAN.md §1.1 for the layout.
 */
object RegionCodec {
    const val VERSION = (6 shl 16) or 8

    // Pixel params bits.
    private const val NOT_GRASS = 1
    private const val HAS_OVERLAYS = 2
    private const val HEIGHT_AS_BYTE = 0x40
    private const val HAS_BIOME = 0x100000
    private const val STATE_NEW = 0x200000
    private const val BIOME_NEW = 0x400000
    private const val BIOME_AS_INT = 0x800000
    private const val TOP_HEIGHT = 0x1000000

    // Overlay params bits.
    private const val OV_NOT_WATER = 1
    private const val OV_CUSTOM_COLOR = 4
    private const val OV_STATE_NEW = 0x400

    fun parse(data: ByteArray): Region {
        val r = ByteReader(data)
        if (r.u8() != 0xFF) {
            throw RegionFormatException("not a versioned region file (pre-1.13 format is not supported)")
        }
        val version = r.i32()
        if (version != VERSION) {
            throw RegionFormatException(
                "unsupported region version ${version ushr 16}.${version and 0xFFFF}, expected 6.8"
            )
        }

        val statePalette = ArrayList<Blob>()
        val biomePalette = ArrayList<BiomeRef>()
        val tileChunks = LinkedHashMap<Int, TileChunk>()

        while (!r.atEnd) {
            val coords = r.u8()
            if (tileChunks.containsKey(coords)) {
                throw RegionFormatException("duplicate tile chunk 0x%02x".format(coords))
            }
            val tiles = arrayOfNulls<Tile>(16)
            for (t in 0 until 16) {
                val firstParams = r.i32()
                if (firstParams == -1) continue
                val pixels = Array(256) { i ->
                    readPixel(r, if (i == 0) firstParams else r.i32(), statePalette, biomePalette)
                }
                tiles[t] = Tile(pixels, r.u8(), r.i32(), r.u8())
            }
            tileChunks[coords] = TileChunk(tiles)
        }

        return Region(tileChunks)
    }

    private fun readPixel(
        r: ByteReader,
        params: Int,
        statePalette: ArrayList<Blob>,
        biomePalette: ArrayList<BiomeRef>,
    ): Pixel {
        var state: Blob? = null
        if (params and NOT_GRASS != 0) {
            state = if (params and STATE_NEW != 0) {
                val start = r.pos
                Nbt.skipNamedTag(r)
                r.sliceFrom(start).also { statePalette.add(it) }
            } else {
                statePalette[r.i32()]
            }
        }

        val heightByte = if (params and HEIGHT_AS_BYTE != 0) r.u8() else null
        val topHeight = if (params and TOP_HEIGHT != 0) r.u8() else null

        var overlays: List<Overlay>? = null
        if (params and HAS_OVERLAYS != 0) {
            overlays = List(r.u8()) {
                val op = r.i32()
                var ovState: Blob? = null
                if (op and OV_NOT_WATER != 0) {
                    ovState = if (op and OV_STATE_NEW != 0) {
                        val start = r.pos
                        Nbt.skipNamedTag(r)
                        r.sliceFrom(start).also { statePalette.add(it) }
                    } else {
                        statePalette[r.i32()]
                    }
                }
                val customColor = if (op and OV_CUSTOM_COLOR != 0) r.i32() else null
                Overlay(op, ovState, customColor)
            }
        }

        var biome: BiomeRef? = null
        if (params and HAS_BIOME != 0) {
            biome = if (params and BIOME_NEW != 0) {
                val asInt = params and BIOME_AS_INT != 0
                val start = r.pos
                if (asInt) r.skip(4) else r.skipUtf()
                BiomeRef(asInt, r.sliceFrom(start)).also { biomePalette.add(it) }
            } else {
                biomePalette[r.i32()]
            }
        }

        return Pixel(params, state, heightByte, topHeight, overlays, biome)
    }

    fun serialize(region: Region): ByteArray {
        val bytes = ByteArrayOutputStream(1 shl 20)
        val out = DataOutputStream(bytes)
        out.writeByte(0xFF)
        out.writeInt(VERSION)

        val stateIndex = HashMap<Blob, Int>()
        val biomeIndex = HashMap<BiomeRef, Int>()

        for ((coords, tileChunk) in region.tileChunks) {
            out.writeByte(coords)
            for (tile in tileChunk.tiles) {
                if (tile == null) {
                    out.writeInt(-1)
                    continue
                }
                for (pixel in tile.pixels) writePixel(out, pixel, stateIndex, biomeIndex)
                out.writeByte(tile.interpretationVersion)
                out.writeInt(tile.caveStart)
                out.writeByte(tile.caveDepth)
            }
        }

        return bytes.toByteArray()
    }

    private fun writePixel(
        out: DataOutputStream,
        pixel: Pixel,
        stateIndex: HashMap<Blob, Int>,
        biomeIndex: HashMap<BiomeRef, Int>,
    ) {
        var params = pixel.params

        val stateIsNew = pixel.state != null && pixel.state !in stateIndex
        params = if (stateIsNew) params or STATE_NEW else params and STATE_NEW.inv()

        val biomeIsNew = pixel.biome != null && pixel.biome !in biomeIndex
        params = if (biomeIsNew) params or BIOME_NEW else params and BIOME_NEW.inv()
        params = if (biomeIsNew && pixel.biome!!.asInt) params or BIOME_AS_INT else params and BIOME_AS_INT.inv()

        out.writeInt(params)

        if (pixel.state != null) {
            if (stateIsNew) {
                out.write(pixel.state.bytes)
                stateIndex[pixel.state] = stateIndex.size
            } else {
                out.writeInt(stateIndex.getValue(pixel.state))
            }
        }

        pixel.heightByte?.let { out.writeByte(it) }
        pixel.topHeight?.let { out.writeByte(it) }

        pixel.overlays?.let { overlays ->
            out.writeByte(overlays.size)
            for (overlay in overlays) {
                val stateNew = overlay.state != null && overlay.state !in stateIndex
                val op = if (stateNew) overlay.params or OV_STATE_NEW else overlay.params and OV_STATE_NEW.inv()
                out.writeInt(op)
                if (overlay.state != null) {
                    if (stateNew) {
                        out.write(overlay.state.bytes)
                        stateIndex[overlay.state] = stateIndex.size
                    } else {
                        out.writeInt(stateIndex.getValue(overlay.state))
                    }
                }
                overlay.customColor?.let { out.writeInt(it) }
            }
        }

        if (pixel.biome != null) {
            if (biomeIsNew) {
                out.write(pixel.biome.blob.bytes)
                biomeIndex[pixel.biome] = biomeIndex.size
            } else {
                out.writeInt(biomeIndex.getValue(pixel.biome))
            }
        }
    }

    /** Extracts the region payload from an X_Z.zip (single entry, normally "region.xaero"). */
    fun readZip(path: Path): ByteArray {
        ZipFile(path.toFile()).use { zip ->
            val entry = zip.getEntry("region.xaero")
                ?: zip.entries().asSequence().firstOrNull()
                ?: throw RegionFormatException("empty zip: $path")
            return zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    fun writeZip(path: Path, data: ByteArray) {
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path).buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("region.xaero"))
            zip.write(data)
            zip.closeEntry()
        }
    }
}
