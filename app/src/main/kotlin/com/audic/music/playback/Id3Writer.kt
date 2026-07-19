package com.audic.music.playback

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Writes ID3v2.3 tags to an MP3 file.
 *
 * Usage: [writeTags] prepends an ID3v2.3 header + frames to [mp3File].
 * Both the file and its metadata are modified in-place using a temp file.
 */
object Id3Writer {

    private const val HEADER_SIZE = 10
    private const val FRAME_HEADER_SIZE = 10 // 4 id + 4 size + 2 flags

    /**
     * Prepends ID3v2.3 metadata (and optionally cover art) to [mp3File].
     * Handles the temp-file dance: reads existing audio, writes ID3 tag + audio back.
     */
    fun writeTags(
        mp3File: File,
        title: String,
        artist: String,
        album: String,
        year: Int?,
        coverArt: ByteArray?,
    ) {
        val existingAudio = if (mp3File.exists()) mp3File.readBytes() else ByteArray(0)
        val frames = buildFrames(title, artist, album, year, coverArt)
        val tagSize = frames.size
        val header = buildHeader(tagSize)

        mp3File.outputStream().use { out ->
            out.write(header)
            out.write(frames)
            out.write(existingAudio)
        }
    }

    private fun buildHeader(frameDataSize: Int): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 0x03 // version 2.3
        header[4] = 0x00
        header[5] = 0x00 // flags
        val syncSize = intToSyncSafe(frameDataSize)
        System.arraycopy(syncSize, 0, header, 6, 4)
        return header
    }

    private fun buildFrames(
        title: String,
        artist: String,
        album: String,
        year: Int?,
        coverArt: ByteArray?,
    ): ByteArray {
        val frames = ByteArrayOutputStream()

        textFrame(frames, "TIT2", title)
        textFrame(frames, "TPE1", artist)
        textFrame(frames, "TALB", album)
        if (year != null) {
            textFrame(frames, "TYER", year.toString())
        }
        if (coverArt != null && coverArt.isNotEmpty()) {
            apicFrame(frames, coverArt)
        }

        return frames.toByteArray()
    }

    private fun textFrame(out: ByteArrayOutputStream, id: String, text: String) {
        if (text.isBlank()) return
        val encoding: Byte = 0x03 // UTF-8
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val frameData = ByteArray(1 + textBytes.size)
        frameData[0] = encoding
        System.arraycopy(textBytes, 0, frameData, 1, textBytes.size)
        writeFrame(out, id, frameData)
    }

    private fun apicFrame(out: ByteArrayOutputStream, imageData: ByteArray) {
        val encoding: Byte = 0x03 // UTF-8
        val mime = "image/jpeg".toByteArray(Charsets.UTF_8)
        val pictureType: Byte = 0x03 // front cover
        // description is empty, just a null byte in the encoding
        val descriptionBytes = byteArrayOf(0) // empty UTF-8 string → just null byte

        val frameData = ByteArrayOutputStream()
        frameData.write(encoding.toInt())
        frameData.write(mime)
        frameData.write(0) // null terminator for MIME
        frameData.write(pictureType.toInt())
        frameData.write(descriptionBytes) // empty description + null terminator
        frameData.write(imageData)

        writeFrame(out, "APIC", frameData.toByteArray())
    }

    private fun writeFrame(out: ByteArrayOutputStream, id: String, data: ByteArray) {
        val header = ByteArray(FRAME_HEADER_SIZE)
        val idBytes = id.toByteArray(Charsets.UTF_8)
        System.arraycopy(idBytes, 0, header, 0, 4)
        val sizeBytes = intToBigEndian(data.size)
        System.arraycopy(sizeBytes, 0, header, 4, 4)
        // flags [8..9] stay 0
        out.write(header)
        out.write(data)
    }

    private fun intToBigEndian(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
    }

    private fun intToSyncSafe(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 21) and 0x7F).toByte(),
            ((value shr 14) and 0x7F).toByte(),
            ((value shr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        )
    }
}
