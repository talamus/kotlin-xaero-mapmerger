package mapmerger

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """XaeroMapmerger — merge two Xaero's World Map save folders (format 6.8, MC 1.20.1)

Usage:
  java -jar XaeroMapmerger.jar <top folder> <bottom folder> <output folder> [--threads N]
  java -jar XaeroMapmerger.jar --selftest <folder>...

The folders are Multiplayer_<server> directories from xaero/world-map/.
The top folder wins where both maps cover the same chunk.
--selftest parses and re-serializes every region zip it finds and verifies the
result is byte-identical (no files are written)."""

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] == "--help" || args[0] == "-h") {
        println(USAGE)
        exitProcess(if (args.isEmpty()) 1 else 0)
    }

    if (args[0] == "--selftest") {
        val folders = args.drop(1).map { Path.of(it) }
        if (folders.isEmpty()) fail("--selftest needs at least one folder")
        folders.filterNot(Files::isDirectory).forEach { fail("not a directory: $it") }
        exitProcess(if (selfTest(folders)) 0 else 1)
    }

    val positional = ArrayList<String>()
    var threads = Runtime.getRuntime().availableProcessors()
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--threads" -> {
                threads = args.getOrNull(++i)?.toIntOrNull()?.takeIf { it >= 1 }
                    ?: fail("--threads needs a positive number")
            }
            else -> {
                if (a.startsWith("--")) fail("unknown option: $a")
                positional.add(a)
            }
        }
        i++
    }
    if (positional.size != 3) fail("expected exactly 3 folders, got ${positional.size}\n\n$USAGE")

    val top = Path.of(positional[0])
    val bottom = Path.of(positional[1])
    val out = Path.of(positional[2])

    if (!Files.isDirectory(top)) fail("top folder does not exist: $top")
    if (!Files.isDirectory(bottom)) fail("bottom folder does not exist: $bottom")
    if (Files.exists(out) && Files.list(out).use { it.findFirst().isPresent }) {
        fail("output folder already exists and is not empty: $out")
    }

    Files.createDirectories(out)
    Merger(top, bottom, out).run(threads)
}

private fun fail(message: String): Nothing {
    System.err.println("Error: $message")
    exitProcess(1)
}
