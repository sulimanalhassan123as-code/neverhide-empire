package com.neverhide.empire.quran

import android.content.Context
import java.io.File

/**
 * QURAN SECTION — offline library manager.
 *
 * All audio lives in the app's private storage:
 *   <files>/quran/<reciterId>/<001..114>.mp3
 *
 * Downloads are resumable (already-complete surahs are skipped),
 * and the library works fully offline once downloaded.
 */
object QuranLibrary {

    data class Reciter(val id: String, val name: String, val url: String)

    /** Available reciters — public per-surah MP3 archives. */
    val reciters = listOf(
        Reciter(
            "alafasy",
            "Mishary Rashid Alafasy",
            "https://download.quranicaudio.com/quran/mishaari_raashid_al_3afaasee"
        ),
        Reciter(
            "ayyoob",
            "Muhammad Ayyoob",
            "https://download.quranicaudio.com/quran/muhammad_ayyoob"
        )
    )

    const val PREFS = "quran_prefs"
    const val KEY_RECITER = "reciter"
    const val KEY_LAST_SURAH = "last_surah"
    const val KEY_LAST_POSITION = "last_position_ms"

    fun reciter(context: Context): Reciter {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECITER, reciters[0].id)
        return reciters.firstOrNull { it.id == id } ?: reciters[0]
    }

    fun dirFor(context: Context, reciter: Reciter): File =
        File(context.filesDir, "quran/${reciter.id}").apply { mkdirs() }

    fun fileFor(context: Context, reciter: Reciter, surahNumber: Int): File =
        File(dirFor(context, reciter), "%03d.mp3".format(surahNumber))

    fun isDownloaded(context: Context, reciter: Reciter, surahNumber: Int): Boolean =
        fileFor(context, reciter, surahNumber).exists()

    fun downloadedCount(context: Context, reciter: Reciter): Int =
        Surahs.all.count { isDownloaded(context, reciter, it.number) }

    fun downloadedSizeBytes(context: Context, reciter: Reciter): Long =
        dirFor(context, reciter).listFiles()?.sumOf { it.length() } ?: 0L

    fun downloadedSurahs(context: Context, reciter: Reciter): List<SurahInfo> =
        Surahs.all.filter { isDownloaded(context, reciter, it.number) }

    /** A file is "in progress" while being fetched (partial temp file). */
    fun isDownloading(context: Context, reciter: Reciter, surahNumber: Int): Boolean =
        File(dirFor(context, reciter), "%03d.part".format(surahNumber)).exists()

    fun delete(context: Context, reciter: Reciter, surahNumber: Int) {
        fileFor(context, reciter, surahNumber).delete()
    }

    fun clearAll(context: Context, reciter: Reciter) {
        dirFor(context, reciter).deleteRecursively()
    }
}
