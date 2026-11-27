package com.mohamed.audioshelf

import android.content.Context
import android.graphics.Typeface
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Only accept font paths in Google's OFL directory, never arbitrary URLs from a catalog.
internal data class SpineFontSource(val family: String, val filename: String)
private val defaultSources = listOf(
    SpineFontSource("unifrakturmaguntia", "UnifrakturMaguntia-Book.ttf"),
    SpineFontSource("uncialantiqua", "UncialAntiqua-Regular.ttf"),
    SpineFontSource("fondamento", "Fondamento-Regular.ttf"),
    SpineFontSource("metamorphous", "Metamorphous-Regular.ttf"),
    SpineFontSource("pirataone", "PirataOne-Regular.ttf"),
    SpineFontSource("audiowide", "Audiowide-Regular.ttf"),
)

private const val DAY = 86400000L
private const val MAX_FONTS = 16
private const val CATALOG_URL = "https://raw.githubusercontent.com/modealpha88-ctrl/audioshelf/main/font-catalog/spine-fonts.tsv"

internal fun parseSpineFontCatalog(text: String): List<SpineFontSource> =
    text.lineSequence().mapNotNull { line ->
        val fields = line.trim().split('\t')
        if (fields.size != 2 || !fields[0].matches(Regex("[a-z0-9]{3,48}")) ||
            !fields[1].matches(Regex("[A-Za-z0-9_-]{1,80}\\.ttf"))) null
        else SpineFontSource(fields[0], fields[1])
    }.distinctBy { it.family }.take(MAX_FONTS).toList()

private fun fontDir(context: Context) = File(context.filesDir, "spine_fonts_downloaded").apply { mkdirs() }

private fun fetchBounded(url: String, maxBytes: Int): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 4000
    connection.readTimeout = 4000
    connection.instanceFollowRedirects = false
    try {
        require(connection.responseCode == 200)
        val length = connection.contentLength
        require(length <= maxBytes)
        return connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() + n <= maxBytes)
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
    } finally { connection.disconnect() }
}

private fun validTtf(bytes: ByteArray) = bytes.size in 1000..2000000 &&
    bytes[0] == 0.toByte() && bytes[1] == 1.toByte() &&
    bytes[2] == 0.toByte() && bytes[3] == 0.toByte()

internal fun downloadedSpineFonts(context: Context): Map<String, Typeface> {
    val root = fontDir(context)
    return root.listFiles().orEmpty().filter {
        it.name.matches(Regex("[a-z0-9]{3,48}\\.ttf")) && it.length() in 1000L..2000000L &&
            File(root, it.nameWithoutExtension + "-OFL.txt").isFile
    }.take(MAX_FONTS).mapNotNull { file ->
        runCatching { file.nameWithoutExtension + "-400" to Typeface.createFromFile(file) }.getOrNull()
    }.toMap()
}

// Called on Dispatchers.IO and only after the bundled fonts score poorly.
// Throttle the entire expansion to once per day, including failed network attempts.
internal fun expandSpineFonts(context: Context) {
    val prefs = context.getSharedPreferences("spine_font_catalog", Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    if (now - prefs.getLong("lastAttempt", 0L) in 0 until DAY) return
    prefs.edit().putLong("lastAttempt", now).apply()
    val root = fontDir(context)
    val catalogFile = File(root, "catalog.tsv")
    runCatching {
        val bytes = fetchBounded(CATALOG_URL, 16384)
        if (parseSpineFontCatalog(bytes.toString(Charsets.UTF_8)).isNotEmpty()) {
            catalogFile.writeBytes(bytes)
        }
    }
    val sources = (runCatching { parseSpineFontCatalog(catalogFile.readText()) }.getOrDefault(emptyList()) +
        defaultSources).distinctBy { it.family }.take(MAX_FONTS)
    val existing = root.listFiles().orEmpty().count { it.extension == "ttf" }
    var added = 0
    // Avoid long network stalls; at most three new fonts per expansion.
    for (source in sources) {
        if (existing + added >= MAX_FONTS || added >= 3) break
        val target = File(root, "${source.family}.ttf")
        if (target.isFile) continue
        val prefix = "https://raw.githubusercontent.com/google/fonts/main/ofl/${source.family}/"
        val ok = runCatching {
            val license = fetchBounded(prefix + "OFL.txt", 32768)
            require(license.toString(Charsets.UTF_8).contains("SIL OPEN FONT LICENSE", ignoreCase = true))
            val bytes = fetchBounded(prefix + source.filename, 2000000)
            require(validTtf(bytes))
            val temporary = File.createTempFile("font-", ".tmp", root)
            try {
                temporary.writeBytes(bytes)
                Typeface.createFromFile(temporary)
                File(root, "${source.family}-OFL.txt").writeBytes(license)
                require(temporary.renameTo(target))
            } finally { temporary.delete() }
        }.isSuccess
        if (ok) added++ else break
    }
}
