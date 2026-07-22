package com.audic.music.playback

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.IBinder
import androidx.core.content.getSystemService
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Transformer
import com.music.innertube.YouTube
import com.audic.music.constants.AudioQuality
import com.audic.music.constants.ExportingSongIdsKey
import com.audic.music.constants.ExportedSongIdsKey
import com.audic.music.utils.YTPlayerUtils
import com.audic.music.utils.dataStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AudioExportService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val songId = intent?.getStringExtra(EXTRA_SONG_ID) ?: return START_NOT_STICKY
        val songTitle = intent.getStringExtra(EXTRA_SONG_TITLE).orEmpty()
        val songArtist = intent.getStringExtra(EXTRA_SONG_ARTIST).orEmpty()
        val songAlbum = intent.getStringExtra(EXTRA_SONG_ALBUM).orEmpty()
        val artworkUrl = intent.getStringExtra(EXTRA_ARTWORK_URL).orEmpty()
        val targetDirectoryUri = intent.getStringExtra(EXTRA_TARGET_DIRECTORY_URI) ?: return START_NOT_STICKY

        serviceScope.launch {
            exportSong(
                songId = songId,
                songTitle = songTitle,
                songArtist = songArtist,
                songAlbum = songAlbum,
                artworkUrl = artworkUrl,
                targetDirectoryUri = targetDirectoryUri,
            )
        }
        return START_NOT_STICKY
    }

    private suspend fun exportSong(
        songId: String,
        songTitle: String,
        songArtist: String,
        songAlbum: String,
        artworkUrl: String,
        targetDirectoryUri: String,
    ) {
        val safeTitle = sanitizeTitle(songTitle.ifBlank { songId })
        addExportingSongId(songId)

        val tempSourceFile = File.createTempFile("export_source_", ".m4a", cacheDir)
        val tempArtworkFile = File.createTempFile("export_cover_", ".jpg", cacheDir)
        val tempMp3File = File.createTempFile("export_result_", ".mp3", cacheDir)

        try {
            val connectivityManager = getSystemService<ConnectivityManager>()
                ?: error("No connectivity manager")
            val playbackData = YTPlayerUtils.playerResponseForPlayback(
                videoId = songId,
                audioQuality = AudioQuality.HIGH,
                connectivityManager = connectivityManager,
            ).getOrThrow()

            val year = fetchSongYear(songId)
            downloadStream(playbackData, tempSourceFile)
            val artworkDownloaded = downloadArtwork(artworkUrl, tempArtworkFile)
            convertToMp3(
                sourceFile = tempSourceFile,
                outputFile = tempMp3File,
                songTitle = songTitle,
                songArtist = songArtist,
                songAlbum = songAlbum,
                year = year,
                artworkFile = if (artworkDownloaded) tempArtworkFile else null,
            )
            writeOutputFile(safeTitle, targetDirectoryUri, tempMp3File)
            addExportedSongId(songId)
        } catch (e: Exception) {
            Timber.e(e, "Export failed for songId=$songId")
        } finally {

            tempSourceFile.delete()
            tempArtworkFile.delete()
            tempMp3File.delete()
            removeExportingSongId(songId)
            stopSelf()
        }
    }
    private suspend fun fetchSongYear(songId: String): Int? =
        YouTube.getMediaInfo(songId)
            .getOrNull()
            ?.uploadDate
            ?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }

    private fun downloadStream(
        playbackData: com.audic.music.utils.YTPlayerUtils.PlaybackData,
        destFile: File,
    ) {
        val totalLength = playbackData.format.contentLength ?: 10_000_000L
        val rangedUrl = "${playbackData.streamUrl}&range=0-$totalLength"
        val request = Request.Builder().url(rangedUrl).build()
        var totalBytes = -1L
        var bytesWritten = 0L

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Stream request failed with ${response.code}")
            val body = response.body ?: error("No response body")
            totalBytes = body.contentLength()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            body.byteStream().use { input ->
                destFile.outputStream().use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesWritten += read
                    }
                    output.flush()
                }
            }
        }
        if (totalBytes > 0 && bytesWritten < totalBytes) {
            error("Incomplete export source: wrote $bytesWritten of $totalBytes bytes")
        }
    }

    private fun downloadArtwork(artworkUrl: String, destFile: File): Boolean {
        if (artworkUrl.isBlank()) return false
        return runCatching {
            httpClient.newCall(Request.Builder().url(artworkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@use
                response.body?.byteStream()?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
            }
        }.isSuccess && destFile.length() > 0L
    }

    private suspend fun convertToMp3(
        sourceFile: File,
        outputFile: File,
        songTitle: String,
        songArtist: String,
        songAlbum: String,
        year: Int?,
        artworkFile: File?,
    ) {
        transcodeWithMedia3(sourceFile, outputFile)

        val coverBytes = artworkFile?.takeIf { it.exists() && it.length() > 0L }?.readBytes()
        Id3Writer.writeTags(
            mp3File = outputFile,
            title = songTitle,
            artist = songArtist,
            album = songAlbum,
            year = year,
            coverArt = coverBytes,
        )
    }

    private suspend fun transcodeWithMedia3(sourceFile: File, outputFile: File) {
        if (outputFile.exists()) outputFile.delete()

        suspendCancellableCoroutine { continuation ->
            val transformer = Transformer.Builder(this@AudioExportService)
                .setAudioMimeType(MimeTypes.AUDIO_MPEG)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: androidx.media3.transformer.ExportResult) {
                        if (!outputFile.exists() || outputFile.length() <= 0L) {
                            continuation.resumeWithException(RuntimeException("Transcoder produced empty file"))
                        } else {
                            continuation.resume(Unit)
                        }
                    }

                    override fun onError(composition: androidx.media3.transformer.Composition, exportResult: androidx.media3.transformer.ExportResult, exception: androidx.media3.transformer.ExportException) {
                        continuation.resumeWithException(exception)
                    }
                })
                .build()

            val mediaItem = MediaItem.fromUri(sourceFile.toURI().toString())
            transformer.start(mediaItem, outputFile.absolutePath)

            continuation.invokeOnCancellation {
                transformer.cancel()
            }
        }
    }

    private fun writeOutputFile(
        safeTitle: String,
        targetDirectoryUri: String,
        sourceFile: File,
    ) {
        val uri = Uri.parse(targetDirectoryUri)
        if (uri.scheme == "file") {
            val folder = File(uri.path ?: error("Invalid export directory"))
            if (!folder.exists() && !folder.mkdirs()) error("Unable to create export directory")
            sourceFile.copyTo(File(folder, "$safeTitle.mp3"), overwrite = true)
        } else {
            val destinationDir = DocumentFile.fromTreeUri(this, uri)
                ?: error("Export directory unavailable")
            val outputFile = destinationDir.createFile("audio/mpeg", "$safeTitle.mp3")
                ?: error("Unable to create output file")
            sourceFile.inputStream().use { input ->
                contentResolver.openOutputStream(outputFile.uri, "w")!!.use { input.copyTo(it) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun addExportedSongId(songId: String) {
        dataStore.edit { preferences ->
            val current = preferences[ExportedSongIdsKey].orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            val updated = listOf(songId) + current.filterNot { it == songId }
            preferences[ExportedSongIdsKey] = updated.take(1000).joinToString(",")
        }
    }

    private suspend fun addExportingSongId(songId: String) {
        dataStore.edit { preferences ->
            val current = preferences[ExportingSongIdsKey].orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            val updated = listOf(songId) + current.filterNot { it == songId }
            preferences[ExportingSongIdsKey] = updated.take(1000).joinToString(",")
        }
    }

    private suspend fun removeExportingSongId(songId: String) {
        dataStore.edit { preferences ->
            val current = preferences[ExportingSongIdsKey].orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            preferences[ExportingSongIdsKey] = current.filterNot { it == songId }.joinToString(",")
        }
    }

    companion object {
        private const val EXTRA_SONG_ID = "extra_song_id"
        private const val EXTRA_SONG_TITLE = "extra_song_title"
        private const val EXTRA_SONG_ARTIST = "extra_song_artist"
        private const val EXTRA_SONG_ALBUM = "extra_song_album"
        private const val EXTRA_ARTWORK_URL = "extra_artwork_url"
        private const val EXTRA_TARGET_DIRECTORY_URI = "extra_target_directory_uri"

        fun start(
            context: Context,
            songId: String,
            songTitle: String,
            songArtist: String,
            songAlbum: String,
            artworkUrl: String,
            targetDirectoryUri: String,
        ) {
            val intent = Intent(context, AudioExportService::class.java).apply {
                putExtra(EXTRA_SONG_ID, songId)
                putExtra(EXTRA_SONG_TITLE, songTitle)
                putExtra(EXTRA_SONG_ARTIST, songArtist)
                putExtra(EXTRA_SONG_ALBUM, songAlbum)
                putExtra(EXTRA_ARTWORK_URL, artworkUrl)
                putExtra(EXTRA_TARGET_DIRECTORY_URI, targetDirectoryUri)
            }
            context.startService(intent)
        }

        private fun sanitizeTitle(title: String): String =
            title
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { "song_${System.currentTimeMillis()}" }

    }
}
