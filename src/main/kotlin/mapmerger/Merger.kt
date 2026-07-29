package mapmerger

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

private val REGION_NAME = Regex("""-?\d+_-?\d+\.zip""")

/** Merges two Multiplayer_<server> world-map folders; top wins tile conflicts. */
class Merger(private val top: Path, private val bottom: Path, private val out: Path) {

    val copied = AtomicInteger()
    val merged = AtomicInteger()
    val failed = ConcurrentLinkedQueue<String>()

    fun run(threads: Int) {
        val topRegions = findRegions(top)
        val bottomRegions = findRegions(bottom)
        val all = (topRegions.keys + bottomRegions.keys).toSortedSet()
        val both = topRegions.keys intersect bottomRegions.keys

        println("Top:    ${topRegions.size} regions in ${top.fileName}")
        println("Bottom: ${bottomRegions.size} regions in ${bottom.fileName}")
        println("Shared (deep merge): ${both.size}, copy-through: ${all.size - both.size}")

        val pool = Executors.newFixedThreadPool(threads)
        try {
            for (rel in all) {
                pool.execute {
                    try {
                        val inTop = rel in topRegions
                        val inBottom = rel in bottomRegions
                        if (inTop && inBottom) {
                            mergeRegionFile(top.resolve(rel), bottom.resolve(rel), out.resolve(rel))
                            merged.incrementAndGet()
                        } else {
                            copyPreservingTime((if (inTop) top else bottom).resolve(rel), out.resolve(rel))
                            copied.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        failed.add("$rel: ${e.message}")
                    }
                }
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.DAYS)
        }

        mergeConfigs()

        println("Done: ${merged.get()} regions merged, ${copied.get()} copied.")
        if (failed.isNotEmpty()) {
            println("FAILED (${failed.size}):")
            failed.forEach { println("  $it") }
        }
    }

    /** Region zips by path relative to [root], skipping cache dirs and lock files. */
    private fun findRegions(root: Path): Map<String, Path> {
        Files.walk(root).use { stream ->
            return stream
                .filter { it.isRegularFile() && REGION_NAME.matches(it.name) }
                .filter { p -> p.relativeTo(root).none { seg -> seg.name.startsWith("cache") } }
                .toList()
                .associateBy { it.relativeTo(root).toString() }
        }
    }

    private fun mergeRegionFile(topFile: Path, bottomFile: Path, outFile: Path) {
        val topRegion = RegionCodec.parse(RegionCodec.readZip(topFile))
        val bottomRegion = RegionCodec.parse(RegionCodec.readZip(bottomFile))

        val coords = (topRegion.tileChunks.keys + bottomRegion.tileChunks.keys).toSortedSet()
        val result = Region(LinkedHashMap())
        for (c in coords) {
            val a = topRegion.tileChunks[c]
            val b = bottomRegion.tileChunks[c]
            result.tileChunks[c] = TileChunk(Array(16) { i -> a?.tiles?.get(i) ?: b?.tiles?.get(i) })
        }

        RegionCodec.writeZip(outFile, RegionCodec.serialize(result))
        val newest = maxOf(Files.getLastModifiedTime(topFile), Files.getLastModifiedTime(bottomFile))
        Files.setLastModifiedTime(outFile, newest)
    }

    private fun copyPreservingTime(from: Path, to: Path) {
        Files.createDirectories(to.parent)
        Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Copies server_config.txt / dimension_config.txt, unioning MWName lines. */
    private fun mergeConfigs() {
        val names = setOf("server_config.txt", "dimension_config.txt")
        val rels = sortedSetOf<String>()
        for (root in listOf(top, bottom)) {
            Files.walk(root).use { stream ->
                stream.filter { it.isRegularFile() && it.name in names }
                    .forEach { rels.add(it.relativeTo(root).toString()) }
            }
        }
        for (rel in rels) {
            val topFile = top.resolve(rel)
            val bottomFile = bottom.resolve(rel)
            val outFile = out.resolve(rel)
            Files.createDirectories(outFile.parent)
            when {
                !Files.exists(bottomFile) -> copyPreservingTime(topFile, outFile)
                !Files.exists(topFile) -> copyPreservingTime(bottomFile, outFile)
                else -> {
                    val topLines = Files.readAllLines(topFile)
                    val knownMw = topLines.mapNotNull { mwId(it) }.toSet()
                    val extra = Files.readAllLines(bottomFile).filter { mwId(it)?.let { id -> id !in knownMw } == true }
                    Files.write(outFile, topLines + extra)
                }
            }
        }
    }

    /** For "MWName:mw$123:Some Name" lines, returns "mw$123"; null otherwise. */
    private fun mwId(line: String): String? {
        if (!line.startsWith("MWName:")) return null
        return line.removePrefix("MWName:").substringBefore(':')
    }
}

/** Parse → serialize → byte-compare every region zip under the given folders. */
fun selfTest(folders: List<Path>): Boolean {
    var ok = 0
    val problems = ArrayList<String>()
    for (root in folders) {
        Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && REGION_NAME.matches(it.name) }
                .filter { p -> p.relativeTo(root).none { seg -> seg.name.startsWith("cache") } }
                .forEach { path ->
                    try {
                        val original = RegionCodec.readZip(path)
                        val rewritten = RegionCodec.serialize(RegionCodec.parse(original))
                        if (rewritten.contentEquals(original)) {
                            ok++
                        } else {
                            val diff = original.indices.firstOrNull { i ->
                                i >= rewritten.size || original[i] != rewritten[i]
                            } ?: original.size
                            problems.add(
                                "$path: round-trip differs at offset $diff " +
                                    "(original ${original.size} B, rewritten ${rewritten.size} B)"
                            )
                        }
                    } catch (e: Exception) {
                        problems.add("$path: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
        }
    }
    println("Self-test: $ok byte-identical round-trips, ${problems.size} problems.")
    problems.take(20).forEach { println("  $it") }
    if (problems.size > 20) println("  ... and ${problems.size - 20} more")
    return problems.isEmpty()
}
