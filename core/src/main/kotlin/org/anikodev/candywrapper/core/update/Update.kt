package org.anikodev.candywrapper.core.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.anikodev.candywrapper.core.http.Http
import org.anikodev.candywrapper.core.json.WireJson

/**
 * The `update.json` CI publishes next to the APK in the rolling `nightly` release
 * (.github/workflows/android.yml). [versionCode] is the CI run number, so it only grows.
 */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val commit: String,
    val publishedAt: String = "",
    val apkName: String,
    val sha256: String,
    val notes: List<String> = emptyList(),
) {
    fun isNewerThan(installedVersionCode: Long): Boolean = versionCode > installedVersionCode

    companion object {
        private val HEX64 = Regex("[0-9a-f]{64}")

        /** Throws [IllegalArgumentException] for anything the updater could not safely act on. */
        fun parse(text: String): UpdateManifest {
            val m = runCatching { WireJson.decodeFromString(serializer(), text) }
                .getOrElse { throw IllegalArgumentException("update.json is not readable: ${it.message}", it) }
            require(m.versionCode > 0) { "update.json has no versionCode" }
            require(m.apkName.isNotBlank() && '/' !in m.apkName) { "update.json has a bad apkName" }
            require(HEX64.matches(m.sha256.lowercase())) { "update.json has a bad sha256" }
            return m.copy(sha256 = m.sha256.lowercase())
        }
    }
}

/** Reads and downloads from the public `nightly` release. Blocking; call it off the main thread. */
class UpdateClient(
    private val releaseBase: String = "https://github.com/Sowyu/CandyWrapper-Android/releases/download/nightly/",
    client: OkHttpClient = Http.client,
) {
    private val manifestClient = client
    // An APK on a slow mobile link outlasts the shared client's 15 s call limit.
    private val downloadClient = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    fun fetchManifest(): UpdateManifest {
        val request = Request.Builder().url(releaseBase + "update.json").cacheControl(CacheControl.FORCE_NETWORK).build()
        manifestClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("update.json: HTTP ${response.code}")
            return UpdateManifest.parse(response.body.string())
        }
    }

    /**
     * Downloads the manifest's APK into [target], hashing as it streams, and deletes it again if the
     * SHA-256 does not match. [onPercent] is called once per whole percent.
     */
    fun download(manifest: UpdateManifest, target: File, onPercent: (Int) -> Unit) {
        val request = Request.Builder().url(releaseBase + manifest.apkName).build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("${manifest.apkName}: HTTP ${response.code}")
            val total = response.body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(1 shl 16)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != lastPercent) { lastPercent = percent; onPercent(percent) }
                    }
                }
            }
            val actual = digest.digest().toHex()
            if (actual != manifest.sha256) {
                target.delete()
                throw IOException("The download does not match update.json (SHA-256 $actual). Check again in a minute.")
            }
        }
    }
}

fun sha256Hex(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 16)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().toHex()
}

/** True when [file] exists and hashes to [expectedHex]. */
fun File.hasSha256(expectedHex: String): Boolean = isFile && inputStream().use { sha256Hex(it) } == expectedHex.lowercase()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
