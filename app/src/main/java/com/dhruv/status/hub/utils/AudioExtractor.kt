package com.dhruv.status.hub.utils

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

object AudioExtractor {

    /**
     * Extracts the audio track from a video file and saves it as an M4A/AAC file.
     */
    @SuppressLint("WrongConstant")
    suspend fun extractAudio(context: Context, videoUri: Uri, outputFileName: String): Uri? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var outputUri: Uri? = null

        try {
            context.contentResolver.openFileDescriptor(videoUri, "r")?.use { fd ->
                extractor.setDataSource(fd.fileDescriptor)
            }

            var audioTrackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex == -1 || format == null) return@withContext null

            // Prepare output file in cache first
            val outputFile = File(context.cacheDir, outputFileName)
            if (outputFile.exists()) outputFile.delete()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val writeAudioTrackIndex = muxer.addTrack(format)
            muxer.start()

            extractor.selectTrack(audioTrackIndex)

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    break
                }
                bufferInfo.presentationTimeUs = extractor.sampleTime
                // Map MediaExtractor flags to MediaCodec flags
                // MediaExtractor.SAMPLE_FLAG_SYNC (1) -> MediaCodec.BUFFER_FLAG_KEY_FRAME (1)
                bufferInfo.flags = extractor.sampleFlags
                
                muxer.writeSampleData(writeAudioTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            muxer = null

            // Move from cache to MediaStore
            outputUri = saveAudioToMediaStore(context, outputFile, outputFileName)
            outputFile.delete()

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            extractor.release()
            try { muxer?.release() } catch (e: Exception) {}
        }

        outputUri
    }

    private fun saveAudioToMediaStore(context: Context, file: File, fileName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + File.separator + "StatusHub")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.insert(collection, values) ?: return null

        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input ->
                    input.copyTo(out)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            return null
        }

        return uri
    }
}
