package app.reader

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap

/** A book on the shelf. [storeId] is its saved copy (null for the built-in sample). */
class ShelfBook(val book: EpubBook, val cover: ImageBitmap?, val storeId: String?) {
    val key: String get() = book.title + "|" + book.author
}

/** Saved reading spot: chapter, paragraph and character. */
data class Locator(val chapter: Int, val block: Int, val char: Int) {
    fun encode() = "$chapter,$block,$char"
    companion object {
        fun decode(s: String?): Locator? = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 3 }?.let { Locator(it[0], it[1], it[2]) }
    }
}

data class Bookmark(val locator: Locator, val created: Long)

enum class SortBy(val label: String) { Recent("Recently read"), Title("Title"), Author("Author"), Added("Date added"), Progress("Progress") }
enum class ShelfView { Grid, List }

class AppController(restoreLastBook: Boolean = true) {
    val shelf = mutableStateListOf<ShelfBook>()
    val back = BackStack()
    private var readingState = mutableStateOf<ShelfBook?>(null)

    /** The open book; remembered so the next launch goes straight back to it. */
    var reading: ShelfBook?
        get() = readingState.value
        set(v) {
            readingState.value = v
            SettingsStore.put("lastBook", v?.key ?: "")
            if (v != null) { SettingsStore.put("seen:" + v.key, nowMillis().toString()); touch() }
        }
    var settings by mutableStateOf(ReaderSettings.decode(SettingsStore.get("settings")))
        private set
    var error by mutableStateOf<String?>(null)
    var requestOpenFile: (() -> Unit)? = null

    /** Bumped whenever saved per-book data changes, so the library recomposes. */
    var metaVersion by mutableIntStateOf(0)
        private set
    private fun touch() { metaVersion++ }

    var sortBy by mutableStateOf(SettingsStore.get("sort")?.let { s -> SortBy.entries.firstOrNull { it.name == s } } ?: SortBy.Recent)
        private set
    var view by mutableStateOf(SettingsStore.get("view")?.let { s -> ShelfView.entries.firstOrNull { it.name == s } } ?: ShelfView.Grid)
        private set
    fun updateSort(s: SortBy) { sortBy = s; SettingsStore.put("sort", s.name) }
    fun updateView(v: ShelfView) { view = v; SettingsStore.put("view", v.name) }

    init {
        runCatching { add(SampleBook.build(), SampleBook.TITLE, persist = false) }
        // books the person added earlier, least recently used first so the newest ends up on top
        for ((id, bytes) in BookStore.loadAll()) {
            if (add(bytes, id, persist = false, quiet = true, knownId = id) == null) BookStore.remove(id)
        }
        if (restoreLastBook) {
            val last = SettingsStore.get("lastBook")
            shelf.firstOrNull { it.key == last }?.let { readingState.value = it }
        }
    }

    fun updateSettings(s: ReaderSettings) {
        settings = s
        SettingsStore.put("settings", s.encode())
    }

    fun add(bytes: ByteArray, name: String, persist: Boolean = true, quiet: Boolean = false, knownId: String? = null): ShelfBook? = try {
        val book = EpubParser.parse(bytes, name.substringBeforeLast('.'))
        val cover = book.coverPath?.let { p -> book.files[p]?.let { decodePicture(it, maxSide = 900) } }
        val id = knownId ?: if (persist) storeId(bytes) else null
        val entry = ShelfBook(book, cover, id)
        shelf.removeAll { it.key == entry.key }
        shelf.add(0, entry)
        if (persist && id != null) BookStore.save(id, bytes)
        if (SettingsStore.get("added:" + entry.key) == null) SettingsStore.put("added:" + entry.key, nowMillis().toString())
        touch()
        entry
    } catch (e: EpubException) {
        if (!quiet) error = e.message; null
    } catch (e: Exception) {
        if (!quiet) error = "Couldn't open $name: ${e.message ?: e::class.simpleName}"; null
    }

    fun open(bytes: ByteArray, name: String) { add(bytes, name)?.let { reading = it } }

    fun remove(b: ShelfBook) {
        shelf.remove(b)
        b.storeId?.let { BookStore.remove(it) }
        if (reading == b) reading = null
        touch()
    }

    /** Stable file name for a book's bytes (size + content hash). */
    private fun storeId(bytes: ByteArray): String =
        "book-" + bytes.size.toString(16) + "-" + (bytes.contentHashCode().toLong() and 0xffffffffL).toString(16)

    // ---- per-book data

    fun position(b: ShelfBook): Locator? = Locator.decode(SettingsStore.get("pos:" + b.key))
    fun progress(b: ShelfBook): Float? = SettingsStore.get("prog:" + b.key)?.toFloatOrNull()
    fun lastOpened(b: ShelfBook): Long = SettingsStore.get("seen:" + b.key)?.toLongOrNull() ?: 0L
    fun added(b: ShelfBook): Long = SettingsStore.get("added:" + b.key)?.toLongOrNull() ?: 0L
    fun chapterTitle(b: ShelfBook): String? = SettingsStore.get("chap:" + b.key)?.takeIf { it.isNotBlank() }
    fun lastPage(b: ShelfBook): Int? = SettingsStore.get("page:" + b.key)?.toIntOrNull()
    fun finished(b: ShelfBook): Boolean = (progress(b) ?: 0f) >= 0.999f

    fun savePosition(b: ShelfBook, l: Locator, page: Int, pageCount: Int, chapter: String) {
        SettingsStore.put("pos:" + b.key, l.encode())
        val fraction = if (pageCount <= 1) 1f else page / (pageCount - 1).toFloat()
        SettingsStore.put("prog:" + b.key, fraction.toString())
        SettingsStore.put("page:" + b.key, (page + 1).toString())
        SettingsStore.put("chap:" + b.key, chapter)
        touch()
    }

    fun markFinished(b: ShelfBook, done: Boolean) {
        SettingsStore.put("prog:" + b.key, if (done) "1.0" else "0.0")
        if (!done) SettingsStore.put("pos:" + b.key, Locator(0, 0, 0).encode())
        touch()
    }

    fun bookmarks(b: ShelfBook): List<Bookmark> =
        SettingsStore.get("bm:" + b.key)?.split(';')?.mapNotNull { e ->
            val p = e.split('@'); val l = Locator.decode(p.getOrNull(0)) ?: return@mapNotNull null
            Bookmark(l, p.getOrNull(1)?.toLongOrNull() ?: 0L)
        } ?: emptyList()

    private fun saveBookmarks(b: ShelfBook, list: List<Bookmark>) {
        SettingsStore.put("bm:" + b.key, list.joinToString(";") { it.locator.encode() + "@" + it.created })
        touch()
    }

    fun addBookmark(b: ShelfBook, l: Locator) {
        val list = bookmarks(b)
        if (list.none { it.locator == l }) saveBookmarks(b, (list + Bookmark(l, nowMillis())).sortedWith(compareBy({ it.locator.chapter }, { it.locator.block }, { it.locator.char })))
    }

    fun removeBookmarks(b: ShelfBook, which: (Bookmark) -> Boolean) = saveBookmarks(b, bookmarks(b).filterNot(which))

    // ---- reading goal

    var goalMinutes by mutableIntStateOf(SettingsStore.get("goal")?.toIntOrNull() ?: 20)
        private set
    fun updateGoal(m: Int) { goalMinutes = m; SettingsStore.put("goal", m.toString()); touch() }

    private fun secondsOn(day: Long): Int = SettingsStore.get("read:$day")?.toIntOrNull() ?: 0
    fun secondsToday(): Int = secondsOn(localEpochDay())

    fun addReadingSeconds(s: Int) {
        val day = localEpochDay()
        SettingsStore.put("read:$day", (secondsOn(day) + s).toString())
        touch()
    }

    /** Days in a row the goal was met, counting today only once it is met. */
    fun streak(): Int {
        val goal = goalMinutes * 60
        var day = localEpochDay()
        if (secondsOn(day) < goal) day--
        var n = 0
        while (n < 3650 && secondsOn(day) >= goal) { n++; day-- }
        return n
    }

    fun sortedShelf(): List<ShelfBook> = when (sortBy) {
        SortBy.Recent -> shelf.sortedWith(compareByDescending<ShelfBook> { lastOpened(it) }.thenByDescending { added(it) })
        SortBy.Title -> shelf.sortedBy { it.book.title.removePrefix("The ").lowercase() }
        SortBy.Author -> shelf.sortedBy { it.book.author.substringAfterLast(' ').lowercase() }
        SortBy.Added -> shelf.sortedByDescending { added(it) }
        SortBy.Progress -> shelf.sortedByDescending { progress(it) ?: -1f }
    }
}

@Composable
fun App(controller: AppController = remember { AppController() }, systemDark: Boolean = false) {
    rememberBundledFonts()
    val settings = controller.settings
    val ambient = if (settings.appearance == Appearance.Ambient) rememberAmbientDark() else null
    val uiDark = settings.appearanceDark(systemDark, ambient)
    val reading = controller.reading

    LaunchedEffect(reading != null, settings.brightness) {
        Hooks.setBrightness(if (reading != null && settings.brightness >= 0f) settings.brightness else null)
    }
    LaunchedEffect(reading != null, settings.lockRotation) { Hooks.lockRotation(reading != null && settings.lockRotation) }

    JustReadTheme(uiDark) {
        CompositionLocalProvider(LocalBackStack provides controller.back) {
            if (reading != null) {
                BackAction { controller.reading = null }
                ReaderScreen(controller, reading, uiDark, onClose = { controller.reading = null })
            } else {
                Library(controller)
            }
            controller.error?.let { msg ->
                val jr = LocalJr.current
                AlertDialog(
                    onDismissRequest = { controller.error = null },
                    confirmButton = { TextButton(onClick = { controller.error = null }) { Text("OK", color = jr.primary) } },
                    title = { Text("Can’t open this book", style = JrType.headline, color = jr.onBackground) },
                    text = { Text(msg, style = JrType.body, color = jr.onVariant) },
                    containerColor = jr.sheet,
                )
            }
        }
    }
}
