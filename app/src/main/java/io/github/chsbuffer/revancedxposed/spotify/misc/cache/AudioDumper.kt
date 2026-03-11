package io.github.chsbuffer.revancedxposed.spotify.misc.cache

import android.app.Application
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadata
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.spotify.misc.lyrics.MusixmatchAPI
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioDumper(private val app: Application) {

    private data class TrackState(
        val title: String,
        val artist: String,
        val album: String,
        val artBytes: ByteArray?,
        var sampleRate: Int = 44100,
        var channels: Int = 2,
        var bitDepth: Int = 16,
        var isFloat: Boolean = false,
        var fileStream: RandomAccessFile? = null,
        var fileObj: File? = null,
        var bytesWritten: Int = 0,
        var trackHash: Int = 0
    )

    @Volatile private var pendingMetadata: TrackState? = null
    @Volatile private var activeState: TrackState? = null

    private val audioLock = Any()

    // Zero-allocation buffer pool for extreme CPU efficiency
    private val threadBuffer = object : ThreadLocal<ByteArray>() {
        override fun initialValue(): ByteArray = ByteArray(131072) // 128KB pool
    }

    private fun getBuffer(requiredSize: Int): ByteArray {
        var buf = threadBuffer.get()!!
        if (buf.size < requiredSize) {
            buf = ByteArray(requiredSize)
            threadBuffer.set(buf)
        }
        return buf
    }

    fun init(classLoader: ClassLoader) {
        XposedBridge.log("ReVancedXposed: Initiating Premium Audiophile Dumper + LRC Engine...")
        hookMetadata(classLoader)
        hookAudioTrack(classLoader)
    }

    private fun hookMetadata(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.media.session.MediaSession", classLoader,
                "setMetadata",
                MediaMetadata::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val metadata = param.args[0] as? MediaMetadata ?: return

                        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown Title"
                        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"
                        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

                        var artBytes: ByteArray? = null
                        var art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

                        if (art != null) {
                            val maxDim = 800
                            if (art.width > maxDim || art.height > maxDim) {
                                val scale = maxDim.toFloat() / Math.max(art.width, art.height)
                                art = Bitmap.createScaledBitmap(art, (art.width * scale).toInt(), (art.height * scale).toInt(), true)
                            }

                            val stream = ByteArrayOutputStream()
                            art.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                            artBytes = stream.toByteArray()
                        }

                        pendingMetadata = TrackState(title, artist, album, artBytes)
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("ReVancedXposed: Failed to hook MediaSession - ${e.message}")
        }
    }

    private fun hookAudioTrack(classLoader: ClassLoader) {
        try {
            val audioTrackClass = XposedHelpers.findClass("android.media.AudioTrack", classLoader)

            val writeHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val track = param.thisObject as? AudioTrack ?: return
                    val writtenCount = param.result as? Int ?: return
                    if (writtenCount <= 0) return

                    val arg0 = param.args[0] ?: return

                    synchronized(audioLock) {
                        if (!ensureActiveTrack(track)) return

                        when (arg0) {
                            is ByteArray -> appendAudioData(arg0, param.args[1] as Int, writtenCount)
                            is ShortArray -> {
                                val offset = param.args[1] as Int
                                val reqBytes = writtenCount * 2
                                val buf = getBuffer(reqBytes)
                                var bIdx = 0
                                for (i in 0 until writtenCount) {
                                    val s = arg0[offset + i].toInt()
                                    buf[bIdx++] = (s and 0xFF).toByte()
                                    buf[bIdx++] = ((s shr 8) and 0xFF).toByte()
                                }
                                appendAudioData(buf, 0, reqBytes)
                            }
                            is FloatArray -> {
                                val offset = param.args[1] as Int
                                val reqBytes = writtenCount * 4
                                val buf = getBuffer(reqBytes)
                                var bIdx = 0
                                for (i in 0 until writtenCount) {
                                    val bits = java.lang.Float.floatToRawIntBits(arg0[offset + i])
                                    buf[bIdx++] = (bits and 0xFF).toByte()
                                    buf[bIdx++] = ((bits shr 8) and 0xFF).toByte()
                                    buf[bIdx++] = ((bits shr 16) and 0xFF).toByte()
                                    buf[bIdx++] = ((bits shr 24) and 0xFF).toByte()
                                }
                                appendAudioData(buf, 0, reqBytes)
                            }
                            is ByteBuffer -> {
                                val buf = getBuffer(writtenCount)
                                val currentPos = arg0.position()
                                val startPos = if (currentPos >= writtenCount) currentPos - writtenCount else 0
                                arg0.position(startPos)
                                arg0.get(buf, 0, writtenCount)
                                arg0.position(currentPos)
                                appendAudioData(buf, 0, writtenCount)
                            }
                        }
                    }
                }
            }

            XposedHelpers.findAndHookMethod(audioTrackClass, "write", ByteArray::class.java, Int::class.java, Int::class.java, writeHook)
            XposedHelpers.findAndHookMethod(audioTrackClass, "write", ByteArray::class.java, Int::class.java, Int::class.java, Int::class.java, writeHook)
            XposedHelpers.findAndHookMethod(audioTrackClass, "write", ShortArray::class.java, Int::class.java, Int::class.java, writeHook)
            XposedHelpers.findAndHookMethod(audioTrackClass, "write", ShortArray::class.java, Int::class.java, Int::class.java, Int::class.java, writeHook)
            XposedHelpers.findAndHookMethod(audioTrackClass, "write", FloatArray::class.java, Int::class.java, Int::class.java, Int::class.java, writeHook)
            XposedHelpers.findAndHookMethod(audioTrackClass, "write", ByteBuffer::class.java, Int::class.java, Int::class.java, writeHook)

            val stateHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    synchronized(audioLock) {
                        activeState?.let { writeWavHeader(it.fileStream!!, it, 0) }
                    }
                }
            }
            XposedHelpers.findAndHookMethod(audioTrackClass, "pause", stateHook)
            XposedHelpers.findAndHookMethod(audioTrackClass, "stop", stateHook)
            XposedHelpers.findAndHookMethod(audioTrackClass, "flush", stateHook)

        } catch (e: Throwable) {
            XposedBridge.log("ReVancedXposed: Failed to hook AudioTrack - ${e.message}")
        }
    }

    private fun ensureActiveTrack(track: AudioTrack): Boolean {
        val nextMeta = pendingMetadata ?: return false
        val currentTrackHash = track.hashCode()

        if (activeState == null || activeState?.title != nextMeta.title || activeState?.artist != nextMeta.artist) {
            finalizeCurrentTrack()

            var safeArtist = nextMeta.artist.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim()
            var safeTitle = nextMeta.title.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim()

            val dumpDir = File("/storage/emulated/0/Android/data/com.spotify.music/files/cached/rips")
            if (!dumpDir.exists()) dumpDir.mkdirs()

            val baseFileName = "$safeArtist - $safeTitle"
            val newFile = File(dumpDir, "$baseFileName.wav")
            val raf = RandomAccessFile(newFile, "rw")

            // 🎯 NEW: Immediately request/dump the .LRC file alongside the .WAV file!
            val lrcFile = File(dumpDir, "$baseFileName.lrc")
            try {
                MusixmatchAPI.downloadLrcToFile(app.applicationContext, nextMeta.title, nextMeta.artist, lrcFile)
            } catch (e: Exception) {
                XposedBridge.log("ReVancedXposed: LRC Export failed - ${e.message}")
            }

            var depth = 16
            var isFloat = false
            when (track.audioFormat) {
                AudioFormat.ENCODING_PCM_8BIT -> depth = 8
                AudioFormat.ENCODING_PCM_16BIT -> depth = 16
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> depth = 24
                AudioFormat.ENCODING_PCM_32BIT -> depth = 32
                AudioFormat.ENCODING_PCM_FLOAT -> { depth = 32; isFloat = true }
            }

            activeState = nextMeta.copy(
                sampleRate = track.sampleRate,
                channels = track.channelCount,
                bitDepth = depth,
                isFloat = isFloat,
                fileStream = raf,
                fileObj = newFile,
                bytesWritten = 0,
                trackHash = currentTrackHash
            )

            raf.write(ByteArray(44))
            return true
        }

        if (activeState!!.trackHash != currentTrackHash) {
            val isSameFormat = track.sampleRate == activeState!!.sampleRate && track.channelCount == activeState!!.channels
            if (isSameFormat) {
                activeState!!.trackHash = currentTrackHash
            } else {
                return false
            }
        }

        return true
    }

    private fun appendAudioData(data: ByteArray, offset: Int, size: Int) {
        activeState?.let { state ->
            try {
                val frameSize = state.channels * (state.bitDepth / 8)
                val alignedSize = size - (size % frameSize)

                if (alignedSize > 0) {
                    state.fileStream?.write(data, offset, alignedSize)
                    state.bytesWritten += alignedSize
                }
            } catch (e: Exception) {}
        }
    }

    private fun finalizeCurrentTrack() {
        activeState?.let { state ->
            try {
                val raf = state.fileStream ?: return

                if (state.bytesWritten < 1_000_000) {
                    raf.close()
                    state.fileObj?.delete()
                    return
                }

                val currentEndPos = 44 + state.bytesWritten.toLong()
                raf.seek(currentEndPos)

                val listChunk = ListInfoBuilder.build(state.title, state.artist, state.album)
                raf.write(listChunk)

                val id3Tag = Id3TagBuilder.build(state.title, state.artist, state.album, state.artBytes)
                val riffId3Header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                riffId3Header.put("id3 ".toByteArray(Charsets.US_ASCII))
                riffId3Header.putInt(id3Tag.size)
                raf.write(riffId3Header.array())
                raf.write(id3Tag)

                var padBytes = 0
                if (id3Tag.size % 2 != 0) {
                    raf.write(0)
                    padBytes = 1
                }

                val extraDataSize = listChunk.size + 8 + id3Tag.size + padBytes
                writeWavHeader(raf, state, extraDataSize)

                raf.close()
                XposedBridge.log("ReVancedXposed: Ripped & Tagged -> ${state.title}")

            } catch (e: Exception) {
                XposedBridge.log("ReVancedXposed: Error finalizing track - ${e.message}")
            }
        }
        activeState = null
    }

    private fun writeWavHeader(raf: RandomAccessFile, state: TrackState, extraMetadataSize: Int) {
        try {
            val audioDataSize = state.bytesWritten
            val totalRiffSize = 36 + audioDataSize + extraMetadataSize
            val byteRate = state.sampleRate * state.channels * (state.bitDepth / 8)
            val blockAlign = state.channels * (state.bitDepth / 8)
            val formatCode = if (state.isFloat) 3 else 1

            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(totalRiffSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(formatCode.toShort())
            header.putShort(state.channels.toShort())
            header.putInt(state.sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(state.bitDepth.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(audioDataSize)

            raf.seek(0)
            raf.write(header.array())
            raf.seek(44 + audioDataSize.toLong())
        } catch (e: Exception) {}
    }

    private object ListInfoBuilder {
        fun build(title: String, artist: String, album: String): ByteArray {
            val out = ByteArrayOutputStream()
            out.write("INFO".toByteArray(Charsets.US_ASCII))
            out.write(createChunk("INAM", title))
            out.write(createChunk("IART", artist))
            if (album.isNotEmpty()) out.write(createChunk("IPRD", album))

            val payload = out.toByteArray()
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            header.put("LIST".toByteArray(Charsets.US_ASCII))
            header.putInt(payload.size)

            val finalChunk = ByteArrayOutputStream()
            finalChunk.write(header.array())
            finalChunk.write(payload)
            return finalChunk.toByteArray()
        }

        private fun createChunk(id: String, text: String): ByteArray {
            val textBytes = text.toByteArray(Charsets.UTF_8)
            val data = textBytes + 0.toByte()
            var pad = if (data.size % 2 != 0) 1 else 0

            val buf = ByteBuffer.allocate(8 + data.size + pad).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(id.toByteArray(Charsets.US_ASCII))
            buf.putInt(data.size)
            buf.put(data)
            if (pad == 1) buf.put(0.toByte())
            return buf.array()
        }
    }

    private object Id3TagBuilder {
        fun build(title: String, artist: String, album: String, artwork: ByteArray?): ByteArray {
            val frames = ByteArrayOutputStream()
            frames.write(createTextFrame("TIT2", title))
            frames.write(createTextFrame("TPE1", artist))
            if (album.isNotEmpty()) frames.write(createTextFrame("TALB", album))
            if (artwork != null) frames.write(createApicFrame(artwork))

            val framesData = frames.toByteArray()
            val header = ByteBuffer.allocate(10)
            header.put("ID3".toByteArray(Charsets.US_ASCII))
            header.putShort(0x0300)
            header.put(0)
            header.put(toSyncSafe(framesData.size))

            val finalTag = ByteArrayOutputStream()
            finalTag.write(header.array())
            finalTag.write(framesData)
            return finalTag.toByteArray()
        }

        private fun createTextFrame(id: String, text: String): ByteArray {
            val textBytes = text.toByteArray(Charsets.UTF_16)
            val frameSize = 1 + textBytes.size + 2
            val buf = ByteBuffer.allocate(10 + frameSize)
            buf.put(id.toByteArray(Charsets.US_ASCII))
            buf.putInt(frameSize)
            buf.putShort(0)
            buf.put(1)
            buf.put(textBytes)
            buf.putShort(0)
            return buf.array()
        }

        private fun createApicFrame(jpegData: ByteArray): ByteArray {
            val mime = "image/jpeg".toByteArray(Charsets.US_ASCII)
            val frameSize = 1 + mime.size + 1 + 1 + 1 + jpegData.size
            val buf = ByteBuffer.allocate(10 + frameSize)
            buf.put("APIC".toByteArray(Charsets.US_ASCII))
            buf.putInt(frameSize)
            buf.putShort(0)
            buf.put(0)
            buf.put(mime)
            buf.put(0)
            buf.put(3)
            buf.put(0)
            buf.put(jpegData)
            return buf.array()
        }

        private fun toSyncSafe(size: Int): ByteArray {
            return byteArrayOf(
                ((size shr 21) and 0x7F).toByte(),
                ((size shr 14) and 0x7F).toByte(),
                ((size shr 7) and 0x7F).toByte(),
                (size and 0x7F).toByte()
            )
        }
    }
}