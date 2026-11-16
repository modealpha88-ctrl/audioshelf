package com.mohamed.audioshelf.data.source

import android.content.Context
import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.AudioChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class SourceProviderDefinition(
    val repositoryUrl: String,
    val id: String,
    val name: String,
    val description: String,
    val attribution: String,
    val browseUrl: String,
    val searchUrl: String,
    val chaptersUrl: String,
) {
    val installKey: String get() = "$repositoryUrl#$id"
    val sourceId: String
        get() = "repo_${repositoryUrl.sha256().take(12)}_${id.replace(Regex("[^A-Za-z0-9_-]"), "_")}"
}

data class SourceRepository(
    val url: String,
    val name: String,
    val description: String,
    val providers: List<SourceProviderDefinition>,
)

class ProviderRepositoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("source_repositories", Context.MODE_PRIVATE)

    fun repositories(): List<SourceRepository> = runCatching {
        val array = JSONArray(preferences.getString(REPOSITORIES, "[]"))
        buildList {
            for (index in 0 until array.length()) add(parseStoredRepository(array.getJSONObject(index)))
        }
    }.getOrDefault(emptyList())

    fun isInstalled(provider: SourceProviderDefinition): Boolean =
        preferences.getStringSet(INSTALLED, emptySet()).orEmpty().contains(provider.installKey)

    fun installedProviders(): List<SourceProviderDefinition> =
        repositories().flatMap(SourceRepository::providers).filter(::isInstalled)

    suspend fun addOrRefresh(rawUrl: String): SourceRepository = withContext(Dispatchers.IO) {
        val url = normalizeSecureUrl(rawUrl)
        val repository = parseRemoteRepository(url, readText(url))
        save(repositories().filterNot { it.url == url } + repository)
        repository
    }

    fun setInstalled(provider: SourceProviderDefinition, installed: Boolean) {
        val current = preferences.getStringSet(INSTALLED, emptySet()).orEmpty().toMutableSet()
        if (installed) current += provider.installKey else current -= provider.installKey
        preferences.edit().putStringSet(INSTALLED, current).apply()
    }

    fun remove(repository: SourceRepository) {
        val providerKeys = repository.providers.mapTo(mutableSetOf(), SourceProviderDefinition::installKey)
        val installed = preferences.getStringSet(INSTALLED, emptySet()).orEmpty() - providerKeys
        preferences.edit()
            .putStringSet(INSTALLED, installed)
            .putString(REPOSITORIES, encodeRepositories(repositories().filterNot { it.url == repository.url }).toString())
            .apply()
    }

    private fun save(repositories: List<SourceRepository>) {
        preferences.edit().putString(REPOSITORIES, encodeRepositories(repositories).toString()).apply()
    }

    private fun encodeRepositories(repositories: List<SourceRepository>) = JSONArray().apply {
        repositories.forEach { repository ->
            put(JSONObject().apply {
                put("url", repository.url)
                put("name", repository.name)
                put("description", repository.description)
                put("providers", JSONArray().apply {
                    repository.providers.forEach { provider -> put(provider.toJson()) }
                })
            })
        }
    }

    private fun parseStoredRepository(root: JSONObject): SourceRepository {
        val url = root.getString("url")
        return SourceRepository(
            url = url,
            name = root.getString("name"),
            description = root.optString("description"),
            providers = root.getJSONArray("providers").objects().map { it.toProvider(url) },
        )
    }

    private fun parseRemoteRepository(url: String, body: String): SourceRepository {
        val root = JSONObject(body)
        require(root.optInt("version", 1) == 1) { "Unsupported repository version" }
        val providers = root.optJSONArray("providers")?.objects()?.map { it.toProvider(url) }.orEmpty()
        require(providers.isNotEmpty()) { "This repository contains no providers" }
        require(providers.distinctBy { it.id }.size == providers.size) { "Provider IDs must be unique" }
        return SourceRepository(
            url = url,
            name = root.optString("name").ifBlank { URI(url).host },
            description = root.optString("description"),
            providers = providers,
        )
    }

    private fun JSONObject.toProvider(repositoryUrl: String): SourceProviderDefinition {
        val provider = SourceProviderDefinition(
            repositoryUrl = repositoryUrl,
            id = getString("id").trim(),
            name = getString("name").trim(),
            description = optString("description").trim(),
            attribution = optString("attribution").trim(),
            browseUrl = getString("browseUrl").trim(),
            searchUrl = optString("searchUrl").trim(),
            chaptersUrl = getString("chaptersUrl").trim(),
        )
        require(provider.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) { "Invalid provider ID" }
        require(provider.name.isNotBlank()) { "Provider name is required" }
        secureTemplate(provider.browseUrl)
        if (provider.searchUrl.isNotBlank()) secureTemplate(provider.searchUrl)
        secureTemplate(provider.chaptersUrl)
        return provider
    }

    private fun SourceProviderDefinition.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("attribution", attribution)
        put("browseUrl", browseUrl)
        put("searchUrl", searchUrl)
        put("chaptersUrl", chaptersUrl)
    }

    private fun readText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return connection.run {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AudioShelf/0.3 (+Android; provider-repository-client)")
            if (responseCode !in 200..299) throw java.io.IOException("Repository returned HTTP $responseCode")
            val bytes = inputStream.use { it.readBytes() }
            require(bytes.size <= MAX_REPOSITORY_BYTES) { "Repository file is too large" }
            bytes.toString(Charsets.UTF_8)
        }.also { connection.disconnect() }
    }

    private fun normalizeSecureUrl(raw: String): String {
        val uri = URI(raw.trim())
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) { "Enter a valid HTTPS repository URL" }
        require(uri.userInfo == null) { "Repository URLs cannot contain credentials" }
        return uri.normalize().toString()
    }

    private fun secureTemplate(value: String) {
        val uri = URI(value.replace("{query}", "query").replace("{page}", "1").replace("{id}", "book"))
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) { "Provider endpoints must use HTTPS" }
        require(uri.userInfo == null) { "Provider endpoints cannot contain credentials" }
    }

    private companion object {
        const val REPOSITORIES = "repositories"
        const val INSTALLED = "installed_providers"
        const val MAX_REPOSITORY_BYTES = 1_048_576
    }
}

class RepositoryAudioSource(private val provider: SourceProviderDefinition) : AudioSource {
    override val id: String = provider.sourceId
    override val name: String = provider.name
    override val attribution: String = provider.attribution.ifBlank { provider.description }

    override suspend fun browse(page: Int): List<AudioBook> =
        fetchBooks(expand(provider.browseUrl, page = page))

    override suspend fun search(query: String, page: Int): List<AudioBook> {
        if (provider.searchUrl.isBlank()) {
            val normalized = query.trim().lowercase()
            return browse(page).filter { normalized in it.title.lowercase() || normalized in it.authors.lowercase() }
        }
        return fetchBooks(expand(provider.searchUrl, query = query, page = page))
    }

    override suspend fun chapters(book: AudioBook): List<AudioChapter> = withContext(Dispatchers.IO) {
        val endpoint = expand(provider.chaptersUrl, id = book.id)
        val root = readJson(endpoint)
        root.array("chapters", "items", "results").objects().mapIndexedNotNull { index, item ->
            val audio = item.optString("audioUrl").ifBlank { item.optString("url") }
            if (audio.isBlank()) return@mapIndexedNotNull null
            AudioChapter(
                id = item.optString("id").ifBlank { "${book.id}:$index" },
                title = item.optString("title").ifBlank { "Chapter ${index + 1}" },
                audioUrl = absoluteSecureUrl(audio, endpoint),
                durationMs = item.optLong("durationMs", 0L).coerceAtLeast(0L),
            )
        }
    }

    private suspend fun fetchBooks(endpoint: String): List<AudioBook> = withContext(Dispatchers.IO) {
        val root = readJson(endpoint)
        root.array("books", "items", "results").objects().mapNotNull { item ->
            val bookId = item.optString("id").trim()
            val title = item.optString("title").trim()
            if (bookId.isBlank() || title.isBlank()) return@mapNotNull null
            AudioBook(
                id = bookId,
                sourceId = id,
                title = title,
                authors = item.text("authors").ifBlank { item.optString("author", "Unknown author") },
                narrators = item.text("narrators"),
                description = item.optString("description"),
                coverUrl = item.optString("coverUrl").takeIf(String::isNotBlank)?.let { absoluteSecureUrl(it, endpoint) }.orEmpty(),
                feedUrl = item.optString("feedUrl"),
                language = item.optString("language"),
            )
        }
    }

    private fun expand(template: String, query: String = "", page: Int = 1, id: String = ""): String =
        template
            .replace("{query}", encode(query.trim()))
            .replace("{page}", page.coerceAtLeast(1).toString())
            .replace("{id}", encode(id))

    private fun readJson(url: String): JsonRoot {
        val connection = URL(url).openConnection() as HttpURLConnection
        val text = connection.run {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AudioShelf/0.3 (+Android; repository-source)")
            if (responseCode !in 200..299) throw java.io.IOException("Provider returned HTTP $responseCode")
            inputStream.bufferedReader().use { it.readText() }
        }.also { connection.disconnect() }
        return if (text.trimStart().startsWith("[")) JsonRoot(array = JSONArray(text)) else JsonRoot(objectValue = JSONObject(text))
    }

    private fun absoluteSecureUrl(value: String, base: String): String {
        val resolved = URL(URL(base), value).toString()
        require(URI(resolved).scheme.equals("https", true)) { "Provider media URLs must use HTTPS" }
        return resolved
    }

    private fun JSONObject.text(key: String): String = when (val value = opt(key)) {
        is JSONArray -> value.strings().joinToString(", ")
        is String -> value
        else -> ""
    }

    private data class JsonRoot(val objectValue: JSONObject? = null, val array: JSONArray? = null) {
        fun array(vararg keys: String): JSONArray = array ?: keys.firstNotNullOfOrNull { objectValue?.optJSONArray(it) } ?: JSONArray()
    }

    private companion object {
        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")
    }
}

private fun JSONArray.objects(): List<JSONObject> = buildList {
    for (index in 0 until length()) optJSONObject(index)?.let(::add)
}

private fun JSONArray.strings(): List<String> = buildList {
    for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
