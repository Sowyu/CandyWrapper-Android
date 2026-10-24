package org.anikodev.candywrapper.core.stash

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import org.anikodev.candywrapper.core.json.WireJson

/** An image kept with a stashed prompt: its file inside the stash's `images` directory. */
@Serializable
data class StashImage(val id: String, val name: String, val mimeType: String, val sizeBytes: Long, val file: String)

/** One stashed prompt (T3 `PromptStashEntry`): the text and the images it was stashed with. */
@Serializable
data class StashEntry(val id: String, val createdAtMs: Long, val prompt: String, val images: List<StashImage> = emptyList()) {
    /** `stashEntrySnippet`: the prompt on one line, at most 90 characters, or how many images it holds. */
    val snippet: String
        get() {
            val text = prompt.trim().replace(Regex("\\s+"), " ")
            if (text.isNotEmpty()) return if (text.length > 90) text.take(90) + "…" else text
            return when (images.size) {
                0 -> "(empty)"
                1 -> "(1 image)"
                else -> "(${images.size} images)"
            }
        }
}

/** An image for a new entry: its bytes are copied into the stash, since the picker's grant does not last. */
class NewStashImage(val name: String, val mimeType: String, val bytes: ByteArray)

/** A restored image, moved out of the stash into [file] so the composer can send it. */
data class RestoredImage(val file: File, val name: String, val mimeType: String, val sizeBytes: Long)

data class Restored(val prompt: String, val images: List<RestoredImage>) {
    /**
     * The composer's text once this prompt comes back into [draft]: after the draft with a blank line
     * between, or in its place when [replace] (a swap, whose draft went into the stash).
     */
    fun into(draft: String, replace: Boolean): String = when {
        replace || draft.isBlank() -> prompt
        prompt.isEmpty() -> draft
        else -> draft.trimEnd() + "\n\n" + prompt
    }
}

/**
 * The prompt stash (T3 `promptStashStore`, CandyWrapper `PromptStashStore`): newest first, at most
 * [MAX_ENTRIES], the oldest evicted past that. It lives on the phone only; nothing reaches the
 * server. `stash.json` in [dir] holds the queue and is replaced atomically on every change, and each
 * image is a file in `images/` beside it. A write that fails leaves the stash as it was and throws,
 * so the caller keeps the draft. Calls block on disk; run them off the main thread.
 *
 * Restored images move to `restored/`, which empties when the stash opens: by then no composer
 * holds them.
 */
class PromptStash(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val images = File(dir, "images")
    private val restored = File(dir, "restored")
    private val file = File(dir, "stash.json")
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<StashEntry>> = _entries

    @Serializable
    private data class Persisted(val version: Int = 1, val entries: List<StashEntry>)

    private fun load(): List<StashEntry> {
        images.mkdirs()
        restored.mkdirs()
        restored.listFiles()?.forEach { it.delete() }
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        // An unreadable file stays unreadable however often it is read; the stash keeps working without it.
        val list = runCatching { WireJson.decodeFromString(Persisted.serializer(), text).entries }.getOrDefault(emptyList())
        // Images with no entry left (a crash between a write and a delete) go.
        val kept = list.flatMap { e -> e.images.map { it.file } }.toSet()
        images.listFiles()?.filter { it.name !in kept }?.forEach { it.delete() }
        return list
    }

    /** Puts [prompt] and [newImages] on top. Returns the entry; throws when nothing could be saved. */
    @Synchronized
    fun stash(prompt: String, newImages: List<NewStashImage>): StashEntry {
        val entry = write(prompt, newImages)
        commit(listOf(entry) + _entries.value, written = entry)
        return entry
    }

    /** Takes [id] out to restore it, moving its images where the composer can read them. Null when it is gone. */
    @Synchronized
    fun restore(id: String): Restored? {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return null
        commit(_entries.value - entry)
        return Restored(entry.prompt, moveOut(entry))
    }

    /**
     * Restores [id] and stashes the draft ([prompt], [newImages]) in its place, in one write: the
     * composer and the stash trade contents.
     */
    @Synchronized
    fun swap(id: String, prompt: String, newImages: List<NewStashImage>): Restored? {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return null
        val draft = write(prompt, newImages)
        commit(listOf(draft) + (_entries.value - entry), written = draft)
        return Restored(entry.prompt, moveOut(entry))
    }

    @Synchronized
    fun delete(id: String) {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        commit(_entries.value - entry)
        removeImages(entry)
    }

    /** Copies a new entry's images in. On failure the copies are removed again. */
    private fun write(prompt: String, newImages: List<NewStashImage>): StashEntry {
        val id = newId()
        val written = ArrayList<StashImage>()
        try {
            for ((i, image) in newImages.withIndex()) {
                val name = "$id-$i"
                atomicWrite(File(images, name), image.bytes)
                written += StashImage("$id-$i", image.name, image.mimeType, image.bytes.size.toLong(), name)
            }
        } catch (e: IOException) {
            written.forEach { File(images, it.file).delete() }
            throw e
        }
        return StashEntry(id, now(), prompt, written)
    }

    /** Writes [next], capped, then publishes it; evicted entries lose their images. Rolls back [written] on failure. */
    private fun commit(next: List<StashEntry>, written: StashEntry? = null) {
        val kept = next.take(MAX_ENTRIES)
        try {
            atomicWrite(file, WireJson.encodeToString(Persisted.serializer(), Persisted(entries = kept)).toByteArray())
        } catch (e: IOException) {
            written?.let(::removeImages)
            throw e
        }
        _entries.value = kept
        next.drop(MAX_ENTRIES).forEach(::removeImages)
    }

    private fun moveOut(entry: StashEntry): List<RestoredImage> = entry.images.mapNotNull { image ->
        val from = File(images, image.file)
        val to = File(restored, image.file)
        if (!from.renameTo(to)) return@mapNotNull null
        RestoredImage(to, image.name, image.mimeType, image.sizeBytes)
    }

    private fun removeImages(entry: StashEntry) = entry.images.forEach { File(images, it.file).delete() }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        /** `MAX_PROMPT_STASH_ENTRIES`. */
        const val MAX_ENTRIES = 20
    }
}
