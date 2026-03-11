package io.github.chsbuffer.revancedxposed.spotify.misc.cache

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CipherSniffer(private val app: Application) {

    // We will set this dynamically so Android can't block us
    private lateinit var logFile: File

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
        XposedBridge.log(message)
        Log.d("V_SNIFFER", message) // Backup logging to standard Android logcat
    }

    fun init(classLoader: ClassLoader) {
        try {
            // Ask Android for the 100% legal, writable cache directory for this app
            val cacheDir = app.cacheDir ?: app.getExternalFilesDir(null)
            logFile = File(cacheDir, "xposedkeys.txt")

            showToast("V [DEBUG]: Sniffer waking up... Target file: ${logFile.absolutePath}")

            logToFile("=== V'S CIPHER SNIFFER SESSION STARTED ===")

            hookCrypto()
            hookFileIO()

            showToast("V [DEBUG]: Hooks deployed! Play a song!")
        } catch (e: Throwable) {
            showToast("V [FATAL]: Crash on boot! Error: ${e.message}")
        }
    }

    private fun hookCrypto() {
        try {
            XposedBridge.hookAllConstructors(
                javax.crypto.spec.SecretKeySpec::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // Check ALL arguments to see if "AES" is mentioned, just in case they use the 4-arg constructor
                        val hasAes = param.args.any { it is String && it.equals("AES", ignoreCase = true) }

                        if (hasAes) {
                            val keyBytes = param.args[0] as? ByteArray ?: return

                            // Spotify track keys are exactly 16 bytes (128-bit)
                            if (keyBytes.size == 16) {
                                val hexKey = keyBytes.joinToString("") { "%02x".format(it) }

                                // SCREAM IT TO THE SCREEN
                                showToast("V: STOLE AES KEY! -> $hexKey")

                                logToFile("[AES KEY SNATCHED] -> $hexKey")
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("ReVancedXposed: Failed to hook Crypto - ${e.message}")
        }
    }

    private fun hookFileIO() {
        try {
            val ioHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val path = when (val arg0 = param.args[0]) {
                        is File -> arg0.absolutePath
                        is String -> arg0
                        else -> return
                    }

                    if (path.contains("spotifycache/Storage") && !path.contains("xposedkeys.txt")) {
                        val action = if (param.method.declaringClass == FileOutputStream::class.java) {
                            "WRITING"
                        } else {
                            "READING"
                        }
                        logToFile("[FILE $action] -> $path")
                    }
                }
            }

            XposedBridge.hookAllConstructors(FileInputStream::class.java, ioHook)
            XposedBridge.hookAllConstructors(FileOutputStream::class.java, ioHook)
            XposedBridge.hookAllConstructors(RandomAccessFile::class.java, ioHook)

        } catch (e: Throwable) {
            XposedBridge.log("ReVancedXposed: Failed to hook File IO - ${e.message}")
        }
    }

    @Synchronized
    private fun logToFile(message: String) {
        try {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val logLine = "[$timestamp] $message\n"

            val fos = FileOutputStream(logFile, true)
            fos.write(logLine.toByteArray())
            fos.close()
        } catch (e: Exception) {
            XposedBridge.log("V [IO ERROR]: Failed to write to text file! ${e.message}")
        }
    }
}