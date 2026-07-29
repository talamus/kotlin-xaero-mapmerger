package mapmerger

/**
 * Minimal NBT walker: skips over a named tag so the caller can capture its raw
 * bytes. No semantic interpretation of the contents is needed anywhere.
 */
object Nbt {
    /** Skips one named tag (type byte + name + payload). Block states are compounds. */
    fun skipNamedTag(r: ByteReader) {
        val type = r.u8()
        if (type == 0) return // TAG_End has no name/payload
        r.skipUtf()
        skipPayload(r, type)
    }

    fun skipPayload(r: ByteReader, type: Int) {
        when (type) {
            0 -> {}
            1 -> r.skip(1)             // byte
            2 -> r.skip(2)             // short
            3 -> r.skip(4)             // int
            4 -> r.skip(8)             // long
            5 -> r.skip(4)             // float
            6 -> r.skip(8)             // double
            7 -> r.skip(r.i32())       // byte array
            8 -> r.skipUtf()           // string
            9 -> {                     // list
                val elementType = r.u8()
                val count = r.i32()
                repeat(count) { skipPayload(r, elementType) }
            }
            10 -> {                    // compound
                while (true) {
                    val t = r.u8()
                    if (t == 0) break
                    r.skipUtf()
                    skipPayload(r, t)
                }
            }
            11 -> r.skip(4 * r.i32())  // int array
            12 -> r.skip(8 * r.i32())  // long array
            else -> throw RegionFormatException("unknown NBT tag type $type at offset ${r.pos}")
        }
    }
}
