package io.github.chsbuffer.revancedxposed.spotify.misc

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.callMethodOrNull
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import java.nio.charset.StandardCharsets
import app.revanced.extension.shared.Utils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.reflect.KProperty0
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.DexKitBridge

// The extension expects the lambda to return ClassData, not Class<*>
typealias FindClassFunc = (DexKitBridge) -> ClassData
typealias FindMethodListFunc = (DexKitBridge) -> List<MethodData>

// ====================================================================
// V'S OMNISCIENT OUTBOUND LOGGER (DEDICATED FILE I/O)
// ====================================================================
object VOmniLogger {
    private const val TAG = "V-OMNI-FIREHOSE"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // We keep a dedicated file just for the raw hex dump
    fun logOutboundHex(source: String, payload: ByteArray, offset: Int = 0, length: Int = payload.size) {
        if (length <= 0) return

        val timestamp = dateFormat.format(Date())
        val actualBytes = if (offset == 0 && length == payload.size) payload else payload.copyOfRange(offset, offset + length)

        val header = "\n\n[$timestamp] 🚀 SOURCE: $source | SIZE: $length bytes\n"
        val hexDump = actualBytes.toOmniHexDump()

        // Logcat for live-tailing (might truncate if massive, but good for watching it flow)
        Log.d(TAG, "OUTBOUND CAUGHT: $source ($length bytes)")

        // Disk write for deep analysis
        try {
            val context = Utils.getContext() ?: return
            val extDir = context.getExternalFilesDir(null) ?: return
            val logFile = File(extDir, "v_omni_outbound_log.txt")

            synchronized(this) {
                logFile.appendText(header + hexDump)
            }
        } catch (e: Exception) {
            Log.e(TAG, "OmniLogger IO Failure: ${e.message}")
        }
    }

    private fun ByteArray.toOmniHexDump(): String {
        val result = java.lang.StringBuilder()
        val hexChars = "0123456789ABCDEF"

        for (i in indices step 16) {
            result.append(String.format("%08X  ", i))
            for (j in 0 until 16) {
                if (i + j < size) {
                    val b = this[i + j].toInt() and 0xFF
                    result.append(hexChars[b shr 4]).append(hexChars[b and 0x0F]).append(" ")
                } else {
                    result.append("   ")
                }
                if (j == 7) result.append(" ")
            }
            result.append(" |")
            for (j in 0 until 16) {
                if (i + j < size) {
                    val b = this[i + j].toInt() and 0xFF
                    if (b in 32..126) result.append(b.toChar()) else result.append('.')
                }
            }
            result.append("|\n")
        }
        return result.toString()
    }
}

// ====================================================================
// THE OMNISCIENT OUTBOUND WIRETAP
// ====================================================================
fun SpotifyHook.InstallOmniscientFirehose() {
    VOmniLogger.logOutboundHex("SYSTEM", "=== V'S OMNISCIENT FIREHOSE ENGAGED ===".toByteArray())

    // ---------------------------------------------------------
    // 1. THE C++ NATIVE CRYPTO CHOKEPOINT (BoringSSL / liborbit)
    // Catches the raw bytes RIGHT before the C++ layer encrypts them.
    // ---------------------------------------------------------
    val cryptoClasses = listOf("org.conscrypt.NativeCrypto", "com.android.org.conscrypt.NativeCrypto")
    for (className in cryptoClasses) {
        val cryptoClass = XposedHelpers.findClassIfExists(className, classLoader) ?: continue

        XposedBridge.hookAllMethods(cryptoClass, "SSL_write", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    // Usually args[2] or args[1] is the byte array depending on Android version. We find it dynamically.
                    val buffer = param.args.find { it is ByteArray } as? ByteArray ?: return
                    VOmniLogger.logOutboundHex("[C++ SSL_WRITE]", buffer)
                } catch (e: Exception) {}
            }
        })
    }

    // ---------------------------------------------------------
    // 2. THE JAVA SOCKET CHOKEPOINT (TCP level)
    // Catches plain HTTP, OkHttp streams, and raw socket writes.
    // ---------------------------------------------------------
    val socketOutClass = XposedHelpers.findClassIfExists("java.net.SocketOutputStream", classLoader)
    socketOutClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "socketWrite", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val bytes = param.args[0] as? ByteArray ?: return
                    val offset = param.args[1] as? Int ?: 0
                    val length = param.args[2] as? Int ?: bytes.size
                    VOmniLogger.logOutboundHex("[JAVA SOCKET_WRITE]", bytes, offset, length)
                } catch (e: Exception) {}
            }
        })
    }

    // ---------------------------------------------------------
    // 3. THE KERNEL POSIX DRAGNET (android.system.Os)
    // Catches native write() and sendto() calls escaping the sandbox.
    // ---------------------------------------------------------
    val osClass = XposedHelpers.findClassIfExists("android.system.Os", classLoader)
    osClass?.let { clazz ->
        // Hook 'write'
        XposedBridge.hookAllMethods(clazz, "write", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val bytes = param.args.find { it is ByteArray } as? ByteArray ?: return
                    val offset = param.args.find { it is Int } as? Int ?: 0
                    val length = param.args.findLast { it is Int } as? Int ?: bytes.size
                    VOmniLogger.logOutboundHex("[KERNEL OS.WRITE]", bytes, offset, length)
                } catch (e: Exception) {}
            }
        })

        // Hook 'sendto' (UDP/TCP raw sends)
        XposedBridge.hookAllMethods(clazz, "sendto", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val bytes = param.args.find { it is ByteArray } as? ByteArray ?: return
                    val offset = param.args.find { it is Int } as? Int ?: 0
                    val length = param.args.findLast { it is Int } as? Int ?: bytes.size
                    VOmniLogger.logOutboundHex("[KERNEL OS.SENDTO]", bytes, offset, length)
                } catch (e: Exception) {}
            }
        })
    }

    // ---------------------------------------------------------
    // 4. THE COSMOS ROUTER (Internal Microservices)
    // We only care about POST, PUT, DELETE (Outbound data)
    // ---------------------------------------------------------
    val routerClass = XposedHelpers.findClassIfExists("com.spotify.cosmos.router.Router", classLoader)
    routerClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "resolve", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val request = param.args[0] ?: return
                    val method = request.callMethodOrNull("getAction") as? String ?: ""

                    if (method == "POST" || method == "PUT" || method == "DELETE") {
                        val uri = request.callMethodOrNull("getUri") as? String ?: "UNKNOWN"
                        val body = request.callMethodOrNull("getBody") as? ByteArray
                        if (body != null && body.isNotEmpty()) {
                            VOmniLogger.logOutboundHex("[COSMOS ROUTER -> $uri]", body)
                        }
                    }
                } catch (e: Exception) {}
            }
        })
    }

    // ---------------------------------------------------------
    // 5. THE HERMES ENGINE (Legacy/Alternative Transport)
    // ---------------------------------------------------------
    val hermesClass = XposedHelpers.findClassIfExists("com.spotify.hermes.Hermes", classLoader)
    hermesClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "send", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val uri = param.args[0] as? String ?: "UNKNOWN"
                    val body = param.args[1] as? ByteArray
                    if (body != null && body.isNotEmpty()) {
                        VOmniLogger.logOutboundHex("[HERMES ENGINE -> $uri]", body)
                    }
                } catch (e: Exception) {}
            }
        })
    }
}

object VTelemetryScanner {
    private const val TAG = "V-TELEMETRY-DRAGNET"

    fun log(message: String) {
        Log.d(TAG, message)
        // If you still have your File Logger from the other script,
        // you can call VLogger.log(message) here so it saves to disk.
    }

    // A smart payload dumper that decides if it's looking at JSON or Binary
    fun dumpPayload(bytes: ByteArray): String {
        if (bytes.isEmpty()) return " [EMPTY PAYLOAD] "

        val asString = String(bytes, StandardCharsets.UTF_8).trim()

        // Is it JSON or plain HTTP?
        if ((asString.startsWith("{") && asString.endsWith("}")) ||
            (asString.startsWith("[") && asString.endsWith("]")) ||
            asString.contains("HTTP/")) {
            return "\n--- 📄 PLAINTEXT / JSON ---\n$asString\n---------------------------"
        }

        // Otherwise, it's Protobuf or encrypted binary. Hex dump it.
        return "\n--- 🧩 RAW HEX (PROTOBUF/BINARY) ---\n${bytes.toHexDump()}\n------------------------------------"
    }

    // Helper to format Hex cleanly
    private fun ByteArray.toHexDump(): String {
        val result = java.lang.StringBuilder()
        val hexChars = "0123456789ABCDEF"
        val maxLimit = minOf(this.size, 1024) // Limit dump to 1KB so logcat doesn't crash

        for (i in 0 until maxLimit step 16) {
            result.append(String.format("%04X  ", i))
            for (j in 0 until 16) {
                if (i + j < maxLimit) {
                    val b = this[i + j].toInt() and 0xFF
                    result.append(hexChars[b shr 4]).append(hexChars[b and 0x0F]).append(" ")
                } else {
                    result.append("   ")
                }
                if (j == 7) result.append(" ")
            }
            result.append(" |")
            for (j in 0 until 16) {
                if (i + j < maxLimit) {
                    val b = this[i + j].toInt() and 0xFF
                    if (b in 32..126) result.append(b.toChar()) else result.append('.')
                }
            }
            result.append("|\n")
        }
        if (this.size > maxLimit) result.append("... [TRUNCATED ${this.size - maxLimit} BYTES]\n")
        return result.toString()
    }

    // Digs through the stack trace to find the specific Spotify class that triggered the request
    fun getCallerClass(): String {
        val stackTrace = Log.getStackTraceString(Throwable())
        val lines = stackTrace.lines()

        // Find the first line that is a Spotify class but NOT our Xposed hook or generic Cosmos routers
        val callerLine = lines.find {
            (it.contains("com.spotify") || it.contains("p.")) &&
                    !it.contains("com.spotify.cosmos.router") &&
                    !it.contains("Xposed")
        }?.trim()

        return callerLine ?: "Unknown / Deep Native Caller"
    }
}

fun SpotifyHook.InstallTelemetryDragnet() {
    VTelemetryScanner.log("=== V'S TELEMETRY DRAGNET ARMED & LISTENING ===")

    // ==========================================================
    // 1. THE INTERNAL ARTERY (COSMOS ROUTER)
    // Catches hm://event-service, hm://player, hm://log, etc.
    // ==========================================================
    val routerClass = XposedHelpers.findClassIfExists("com.spotify.cosmos.router.Router", classLoader)

    routerClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "resolve", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val request = param.args[0] ?: return
                    val uri = request.callMethodOrNull("getUri") as? String ?: "UNKNOWN_URI"

                    // TARGET LOCK: We only want to log telemetry, events, logging, and playback context.
                    // We don't care about fetching album art right now.
                    if (uri.contains("event", ignoreCase = true) ||
                        uri.contains("telemetry", ignoreCase = true) ||
                        uri.contains("log", ignoreCase = true) ||
                        uri.contains("playback", ignoreCase = true) ||
                        uri.contains("context", ignoreCase = true)) {

                        val method = request.callMethodOrNull("getAction") as? String ?: "UNKNOWN"
                        val body = request.callMethodOrNull("getBody") as? ByteArray
                        val caller = VTelemetryScanner.getCallerClass()

                        VTelemetryScanner.log(" ")
                        VTelemetryScanner.log("🚨 [COSMOS SNITCH DETECTED] 🚨")
                        VTelemetryScanner.log("📍 DESTINATION: $uri")
                        VTelemetryScanner.log("🛠️ METHOD: $method")
                        VTelemetryScanner.log("🕵️ CALLER ORIGIN: $caller")

                        if (body != null) {
                            VTelemetryScanner.log("📦 PAYLOAD SIZE: ${body.size} bytes")
                            VTelemetryScanner.log(VTelemetryScanner.dumpPayload(body))
                        } else {
                            VTelemetryScanner.log("📦 PAYLOAD: [EMPTY]")
                        }
                        VTelemetryScanner.log("-----------------------------------------")
                    }
                } catch (e: Exception) {
                    VTelemetryScanner.log("💥 Dragnet Error (Cosmos): ${e.message}")
                }
            }
        })
    } ?: VTelemetryScanner.log("⚠️ Cosmos Router not found!")

    // ==========================================================
    // 2. THE EXTERNAL ARTERY (OKHTTP)
    // Catches anything attempting to bypass Cosmos and go straight to the web.
    // ==========================================================
    val okHttpCallClass = XposedHelpers.findClassIfExists("okhttp3.RealCall", classLoader)

    okHttpCallClass?.let { clazz ->
        // Hook both execute (synchronous) and enqueue (asynchronous)
        val interceptLogic = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    // In RealCall, the original Request object is usually a field named 'originalRequest'
                    // We'll just grab the URL to see if it's a tracking endpoint.
                    val request = XposedHelpers.getObjectField(param.thisObject, "originalRequest")
                    val urlObject = request.callMethodOrNull("url")
                    val url = urlObject.toString()

                    if (url.contains("events") || url.contains("track") || url.contains("log")) {
                        val caller = VTelemetryScanner.getCallerClass()

                        VTelemetryScanner.log(" ")
                        VTelemetryScanner.log("🌐 [EXTERNAL SNITCH DETECTED] 🌐")
                        VTelemetryScanner.log("📍 DESTINATION: $url")
                        VTelemetryScanner.log("🕵️ CALLER ORIGIN: $caller")

                        // Extracting body from OkHttp is tricky because it's a stream,
                        // but we can try to check if it has a body.
                        val body = request.callMethodOrNull("body")
                        if (body != null) {
                            val contentLength = body.callMethodOrNull("contentLength") ?: -1
                            VTelemetryScanner.log("📦 HAS BODY: YES (Content-Length: $contentLength)")
                            // Note: We don't consume the OkHttp body here because reading an Okio source
                            // consumes the stream and breaks the actual network request.
                        }
                        VTelemetryScanner.log("-----------------------------------------")
                    }
                } catch (e: Exception) {
                    // Fail silently so we don't crash network threads
                }
            }
        }

        XposedBridge.hookAllMethods(clazz, "execute", interceptLogic)
        XposedBridge.hookAllMethods(clazz, "enqueue", interceptLogic)

    } ?: VTelemetryScanner.log("⚠️ OkHttp RealCall not found!")
}

object VUpstreamSniffer {
    private const val TAG = "V-UPSTREAM-HUNTER"

    fun log(message: String) {
        Log.d(TAG, message)
    }

    fun safeString(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return "[EMPTY]"
        val str = String(bytes, StandardCharsets.UTF_8)
        // If it looks like JSON, return it. Otherwise, return hex.
        return if (str.contains("{") || str.contains("interaction_id")) str else bytes.toHexDump()
    }

    private fun ByteArray.toHexDump(): String {
        return joinToString(" ") { String.format("%02X", it) }
    }
}

fun SpotifyHook.InstallUpstreamSniffer() {
    VUpstreamSniffer.log("=== V'S UPSTREAM HUNTER ENGAGED (HARDENED) ===")
    VUpstreamSniffer.log("Hunting for Pre-Hash PlayOptions & TrackTransitionEvents...")

    // ==========================================================
    // 1. THE COSMOS PRE-HASH INSPECTOR
    // ==========================================================
    runCatching {
        val routerClass = XposedHelpers.findClassIfExists("com.spotify.cosmos.router.Router", classLoader)
        routerClass?.let { clazz ->
            XposedBridge.hookAllMethods(clazz, "resolve", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val request = param.args[0] ?: return
                        val uri = request.callMethodOrNull("getUri") as? String ?: return

                        if (uri.contains("hm://player/v2/play") || uri.contains("hm://event-service/v1/events")) {
                            val body = request.callMethodOrNull("getBody") as? ByteArray
                            val headers = request.callMethodOrNull("getHeaders") as? Map<*, *>

                            VUpstreamSniffer.log("\n🚨 [UPSTREAM TARGET ACQUIRED] 🚨")
                            VUpstreamSniffer.log("📍 URI: $uri")

                            val hasIntegrityHash = headers?.keys?.any { it.toString().contains("Session-Integrity") } == true
                            VUpstreamSniffer.log("🔒 HAS CRYPTO HASH: ${if (hasIntegrityHash) "YES (Too Late!)" else "NO (VULNERABLE!)"}")

                            val payloadStr = VUpstreamSniffer.safeString(body)

                            if (payloadStr.contains("is_interactive") ||
                                payloadStr.contains("interaction_id") ||
                                payloadStr.contains("track_click") ||
                                payloadStr.contains("TrackTransitionEvent")) {

                                VUpstreamSniffer.log("📦 THE SNITCH PAYLOAD:\n$payloadStr")
                            }
                        }
                    } catch (e: Exception) {}
                }
            })
        }
    }.onFailure { VUpstreamSniffer.log("💥 Cosmos Hook Failed: ${it.message}") }

    // ==========================================================
    // 2. THE PLAYER ARCHITECTURE WIRE-TAP (CRASH-PROOF)
    // ==========================================================

    // We grab both the abstract builder and the generated AutoValue builder
    val playOptionsClasses = listOfNotNull(
        XposedHelpers.findClassIfExists("com.spotify.player.model.command.options.PlayOptions\$Builder", classLoader),
        XposedHelpers.findClassIfExists("com.spotify.player.model.command.options.AutoValue_PlayOptions\$Builder", classLoader)
    )

    playOptionsClasses.forEach { clazz ->
        runCatching {
            clazz.declaredMethods.forEach { method ->
                // CRITICAL FIX: Explicitly check that the method is NOT abstract before hooking
                if (method.name == "build" && !java.lang.reflect.Modifier.isAbstract(method.modifiers)) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            VUpstreamSniffer.log("🛠️ [PLAYER] ${clazz.simpleName}.build() called!")
                            try {
                                val fields = param.thisObject.javaClass.declaredFields
                                for (field in fields) {
                                    field.isAccessible = true
                                    val value = field.get(param.thisObject)
                                    if (field.name.contains("interactive", true)) {
                                        VUpstreamSniffer.log("⚠️ FOUND INTERACTIVE FLAG: ${field.name} = $value")
                                    }
                                }
                            } catch (e: Exception) { /* Silently catch reflection errors */ }
                        }
                    })
                }
            }
        }.onFailure { VUpstreamSniffer.log("💥 PlayOptions Hook Failed on ${clazz.name}: ${it.message}") }
    }

    val playOriginClasses = listOfNotNull(
        XposedHelpers.findClassIfExists("com.spotify.player.model.PlayOrigin\$Builder", classLoader),
        XposedHelpers.findClassIfExists("com.spotify.player.model.AutoValue_PlayOrigin\$Builder", classLoader)
    )

    playOriginClasses.forEach { clazz ->
        runCatching {
            clazz.declaredMethods.forEach { method ->
                // CRITICAL FIX: Explicitly check that the method is NOT abstract before hooking
                if (method.name == "build" && !java.lang.reflect.Modifier.isAbstract(method.modifiers)) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            VUpstreamSniffer.log("🛠️ [PLAYER] ${clazz.simpleName}.build() called!")
                            try {
                                val fields = param.thisObject.javaClass.declaredFields
                                for (field in fields) {
                                    field.isAccessible = true
                                    val value = field.get(param.thisObject)
                                    if (field.name.contains("featureIdentifier", true) || field.name.contains("reason", true)) {
                                        VUpstreamSniffer.log("⚠️ FOUND PLAY ORIGIN: ${field.name} = $value")
                                    }
                                }
                            } catch (e: Exception) { /* Silently catch reflection errors */ }
                        }
                    })
                }
            }
        }.onFailure { VUpstreamSniffer.log("💥 PlayOrigin Hook Failed on ${clazz.name}: ${it.message}") }
    }
}

object VGodEyeLogger {
    private const val TAG = "V-GOD-EYE"
    fun log(message: String) {
        Log.d(TAG, message)
        // Feel free to append this to a text file if needed
    }
}

fun SpotifyHook.InstallGodEyeLogger() {
    VGodEyeLogger.log("=== V'S GOD-EYE V2: THE SNITCH RADAR ===")

    // List of words that absolutely should NEVER reach the server
    val snitchWords = listOf(
        "\"is_interactive\":true", "\"is_interactive\": true",
        "fwdbtn", "clickrow", "track_row", "playlist_view",
        "album_view", "explicit_skip\":true", "explicit_skip\": true",
        "manual_selection", "\"intent_type\":\"click\""
    )

    val requestBodyClass = XposedHelpers.findClassIfExists("okhttp3.RequestBody", classLoader)
    requestBodyClass?.let { clazz ->
        val writeToMethods = clazz.declaredMethods.filter { it.name == "writeTo" }
        writeToMethods.forEach { method ->
            XposedBridge.hookMethod(method, object : de.robv.android.xposed.XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val bodyString = param.thisObject.toString()
                        if (bodyString.contains("{")) {
                            val caughtSnitches = snitchWords.filter { bodyString.contains(it) }
                            if (caughtSnitches.isNotEmpty()) {
                                VGodEyeLogger.log("\n🚨 [GOD-EYE OKHTTP SNITCH RADAR] 🚨")
                                VGodEyeLogger.log("⚠️ LEAKED VARIABLES: $caughtSnitches")
                                VGodEyeLogger.log("📄 FULL PAYLOAD:\n$bodyString\n")
                            }
                        }
                    } catch (e: Exception) {}
                }
            })
        }
    }

    val gzipClass = XposedHelpers.findClassIfExists("java.util.zip.GZIPOutputStream", classLoader)
    val deflaterClass = XposedHelpers.findClassIfExists("java.util.zip.DeflaterOutputStream", classLoader)

    listOfNotNull(gzipClass, deflaterClass).forEach { clazz ->
        XposedBridge.hookAllMethods(clazz, "write", object : de.robv.android.xposed.XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val bytes = param.args.find { it is ByteArray } as? ByteArray ?: return
                    val offset = param.args.find { it is Int } as? Int ?: 0
                    val length = param.args.findLast { it is Int } as? Int ?: bytes.size

                    if (length < 10) return
                    val payload = String(bytes, offset, length, java.nio.charset.StandardCharsets.UTF_8)

                    if (payload.contains("{")) {
                        val caughtSnitches = snitchWords.filter { payload.contains(it) }

                        if (caughtSnitches.isNotEmpty()) {
                            VGodEyeLogger.log("\n🚨 [GOD-EYE COMPRESSION SNITCH RADAR] 🚨")
                            VGodEyeLogger.log("⚠️ LEAKED VARIABLES: $caughtSnitches")
                            VGodEyeLogger.log("📄 FULL PAYLOAD:\n$payload\n")
                        } else if (payload.contains("interaction_id") || payload.contains("event")) {
                            // Log safe/scrubbed payloads just to verify they look good
                            VGodEyeLogger.log("\n✅ [GOD-EYE: CLEAN PAYLOAD VERIFIED]")
                            VGodEyeLogger.log(payload + "\n")
                        }
                    }
                } catch (e: Exception) {}
            }
        })
    }
}

// ====================================================================
// V'S OKHTTP INTERCEPTOR DRAGNET (The Final Checkpoint)
// ====================================================================
fun SpotifyHook.InstallOkHttpInterceptorDragnet() {
    VGodEyeLogger.log("=== V'S OKHTTP INTERCEPTOR DRAGNET ARMED ===")

    val interceptorChainClass = XposedHelpers.findClassIfExists("okhttp3.internal.http.RealInterceptorChain", classLoader)

    interceptorChainClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "proceed", object : de.robv.android.xposed.XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val request = param.args[0] // okhttp3.Request
                    val url = request.callMethodOrNull("url")?.toString() ?: return

                    if (url.contains("event", true) || url.contains("play", true) || url.contains("log", true)) {
                        val method = request.callMethodOrNull("method")
                        val headers = request.callMethodOrNull("headers")

                        VGodEyeLogger.log("\n🛑 [FINAL OKHTTP CHECKPOINT] 🛑")
                        VGodEyeLogger.log("📍 URL: $url")
                        VGodEyeLogger.log("🛠️ METHOD: $method")

                        val hasHash = headers.toString().contains("Session-Integrity")
                        VGodEyeLogger.log("🔒 INTEGRITY HASH PRESENT: $hasHash")

                        // Look for our specific snitch headers (sometimes they pass info in headers!)
                        if (headers.toString().contains("premium") || headers.toString().contains("interactive")) {
                            VGodEyeLogger.log("⚠️ SNITCH HEADER DETECTED:\n$headers")
                        }
                    }
                } catch (e: Exception) {}
            }
        })
    }
}

// ====================================================================
// V'S EVENT-SENDER WIRETAP (The Ghost Pipeline)
// ====================================================================
fun SpotifyHook.InstallEventSenderWiretap() {
    VGodEyeLogger.log("=== V'S EVENT-SENDER WIRETAP ARMED ===")

    // Spotify uses a dedicated EventSender API that sometimes bypasses standard routers
    val eventSenderClasses = listOf(
        "com.spotify.eventsender.core.EventSenderImpl",
        "com.spotify.eventsender.api.EventSender"
    )

    eventSenderClasses.forEach { className ->
        val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach

        XposedBridge.hookAllMethods(clazz, "send", object : de.robv.android.xposed.XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    // Usually args[0] is an EventBuilder or EventMessage
                    val event = param.args[0] ?: return
                    val eventName = event.callMethodOrNull("getName") as? String ?: event.javaClass.simpleName

                    if (eventName.contains("Transition") || eventName.contains("Play") || eventName.contains("Skip")) {
                        VGodEyeLogger.log("\n👻 [EVENT-SENDER GHOST PIPELINE] 👻")
                        VGodEyeLogger.log("📝 EVENT TRIGGERED: $eventName")

                        // Dump all fields of the event object using reflection
                        val fields = event.javaClass.declaredFields
                        for (field in fields) {
                            field.isAccessible = true
                            val value = field.get(event)
                            val valueStr = value.toString().lowercase()

                            // Check if this ghost pipeline is leaking intent
                            if (valueStr.contains("fwdbtn") || valueStr.contains("interactive") || valueStr.contains("click")) {
                                VGodEyeLogger.log("⚠️ GHOST LEAK DETECTED: ${field.name} = $value")
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        })
    }
}

// ====================================================================
// V'S DATABASE WIRETAP (The Dead Drop Sniffer)
// ====================================================================
fun SpotifyHook.InstallDatabaseWiretap() {
    VGodEyeLogger.log("=== V'S DATABASE WIRETAP ARMED ===")

    val sqliteClass = XposedHelpers.findClassIfExists("android.database.sqlite.SQLiteDatabase", classLoader)

    sqliteClass?.let { clazz ->
        // Hook 'insert' and 'insertWithOnConflict'
        val insertLogic = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val table = param.args[0] as? String ?: return

                    // We only care about tracking/event/telemetry tables
                    if (table.contains("event", true) || table.contains("log", true) || table.contains("track", true)) {
                        val contentValues = param.args[2] as? android.content.ContentValues ?: return
                        val valuesStr = contentValues.toString().lowercase()

                        // Check if they are dropping snitch data into the DB for a delayed send
                        if (valuesStr.contains("fwdbtn") || valuesStr.contains("interactive") ||
                            valuesStr.contains("click") || valuesStr.contains("premium")) {

                            VGodEyeLogger.log("\n🗄️ [DATABASE DEAD DROP DETECTED] 🗄️")
                            VGodEyeLogger.log("📍 TABLE: $table")
                            VGodEyeLogger.log("⚠️ SNITCH DATA: $valuesStr")
                            VGodEyeLogger.log("-----------------------------------------")
                        }
                    }
                } catch (e: Exception) {}
            }
        }

        XposedBridge.hookAllMethods(clazz, "insert", insertLogic)
        XposedBridge.hookAllMethods(clazz, "insertWithOnConflict", insertLogic)
    }
}

// ====================================================================
// V'S JNI BOUNDARY DRAGNET (The Native Trapdoor Sniffer)
// ====================================================================
fun SpotifyHook.InstallJniBoundaryDragnet() {
    VGodEyeLogger.log("=== V'S JNI BOUNDARY DRAGNET: TARGETED (FIXED) ===")

    // Target the specific C++ bridge classes instead of every Object in the app
    val jniClasses = listOf(
        "com.spotify.cosmos.router.jni.CosmosRouter",
        "com.spotify.orbit.jni.OrbitSession",
        "com.spotify.connectivity.NativeSession",
        "com.spotify.connectivity.Native"
    )

    jniClasses.forEach { className ->
        val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach

        // Hook all methods in the JNI bridge that take a String or ByteArray
        clazz.declaredMethods.forEach { method ->
            if (method.parameterTypes.any { it == String::class.java || it == ByteArray::class.java }) {

                XposedBridge.hookMethod(method, object : de.robv.android.xposed.XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            for (arg in param.args) {
                                val argStr = when (arg) {
                                    is String -> arg.lowercase()
                                    is ByteArray -> String(arg, java.nio.charset.StandardCharsets.UTF_8).lowercase()
                                    else -> continue
                                }

                                if (argStr.contains("fwdbtn") || argStr.contains("interactive") || argStr.contains("click")) {
                                    VGodEyeLogger.log("\n🚪 [JNI TRAPDOOR LEAK DETECTED] 🚪")
                                    VGodEyeLogger.log("📍 METHOD: ${clazz.simpleName}.${method.name}")
                                    VGodEyeLogger.log("⚠️ PASSED TO C++: $argStr")
                                    VGodEyeLogger.log("-----------------------------------------")
                                }
                            }
                        } catch (e: Exception) {}
                    }
                })
            }
        }
    }
}

// ====================================================================
// V'S DEDICATED KEY LOGGER (UPGRADED: DEEP TRACE + DUMP FLAG)
// ====================================================================
object VKeyLogger {
    private const val TAG = "V-KEY-LOGGER"
    private val dateFormat = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    // The missing flag for your attribute dumper!
    var hasDumpedInitialKeys = false

    fun log(message: String) {
        android.util.Log.d(TAG, message)
        try {
            val context = app.revanced.extension.shared.Utils.getContext() ?: return
            val extDir = context.getExternalFilesDir(null) ?: return
            val logFile = java.io.File(extDir, "v_keys_log.txt")
            val timestamp = dateFormat.format(java.util.Date())
            synchronized(this) {
                logFile.appendText("[$timestamp] $message\n")
            }
        } catch (e: Exception) {}
    }

    // THE DEEP X-RAY: Grabs the top 5 layers of the actual Spotify call stack
    fun getCaller(): String {
        val stackTrace = Throwable().stackTrace
        val traceBuilder = java.lang.StringBuilder()
        var count = 0

        for (element in stackTrace) {
            val className = element.className

            // Skip all the hooking framework and Android OS noise
            if (className.contains("Xposed", true) ||
                className.contains("LSPosed", true) ||
                className.contains("LSPHooker", true) ||
                className.contains("chsbuffer", true) ||
                className.startsWith("java.lang.reflect") ||
                className.startsWith("dalvik.system") ||
                className.startsWith("android.app") ||
                className.startsWith("android.os") ||
                className.contains("VKeyLogger") ||
                className.contains("VTraceMap")
            ) {
                continue // Skip to the real stuff
            }

            traceBuilder.append("\n    -> $className.${element.methodName}(Line: ${element.lineNumber})")
            count++

            // We only need the top 5 Spotify classes to see the chain of command
            if (count >= 5) break
        }

        return if (traceBuilder.isNotEmpty()) traceBuilder.toString() else "Unknown/Native Boundary"
    }
}

// ====================================================================
// V'S INESCAPABLE HONEYPOT MAP (UPGRADED MASS-DRAGNET)
// ====================================================================
class VTraceMap(private val baseMap: Map<String, Any?>) : Map<String, Any?> {

    // The master list of every premium override key we want to track.
    // If Spotify's internal code breathes on any of these, we log the exact caller.
    private val targetKeys = setOf(
        "ads", "player-license", "shuffle", "on-demand", "streaming",
        "pick-and-shuffle", "streaming-rules", "nft-disabled",
        "premium-only-market-mobile", "premium-tab-lock", "premium-mini",
        "has-been-premium-mini", "is-eligible-premium-unboxing", "libspotify",
        "type", "catalogue", "name", "financial-product", "audio-quality",
        "has-audiobooks-subscription", "jam-social-session", "social-session",
        "social-session-free-tier", "is_email_verified", "obfuscate-restricted-tracks",
        "smart-shuffle", "dj-accessible", "enable-dj", "ai-playlists",
        "can_use_superbird", "tablet-free"
    )

    override val entries: Set<Map.Entry<String, Any?>>
        get() {
            // Uncomment if you want to see who is looping over the entire map
            VKeyLogger.log("🕵️ [HONEYPOT] Map Iterated (entries) by: ${VKeyLogger.getCaller()}")
            return baseMap.entries
        }
    override val keys: Set<String>
        get() = baseMap.keys
    override val size: Int
        get() = baseMap.size
    override val values: Collection<Any?>
        get() = baseMap.values
    override fun isEmpty(): Boolean = baseMap.isEmpty()

    override fun containsKey(key: String): Boolean {
        if (key in targetKeys) {
            VKeyLogger.log("🕵️ [HONEYPOT] Checked containsKey('$key') | Caller: ${VKeyLogger.getCaller()}")
        }
        return baseMap.containsKey(key)
    }

    override fun containsValue(value: Any?): Boolean = baseMap.containsValue(value)

    override operator fun get(key: String): Any? {
        if (key in targetKeys) {
            VKeyLogger.log("🕵️ [HONEYPOT] Read get('$key') | Caller: ${VKeyLogger.getCaller()}")
        }
        return baseMap[key]
    }

    override fun getOrDefault(key: String, defaultValue: Any?): Any? {
        if (key in targetKeys) {
            VKeyLogger.log("🕵️ [HONEYPOT] Read getOrDefault('$key') | Caller: ${VKeyLogger.getCaller()}")
        }
        return baseMap.getOrDefault(key, defaultValue)
    }
}

// ====================================================================
// V'S TARGETED INTERROGATION: p.v9e0
// ====================================================================
fun SpotifyHook.InterrogateV9E0() {
    VGodEyeLogger.log("=== V'S INTERROGATION: HUNTING p.v9e0 ===")

    val targetClass = XposedHelpers.findClassIfExists("p.v9e0", classLoader)

    if (targetClass != null) {
        // We hook ALL methods named 'l' in this class just to be safe
        XposedBridge.hookAllMethods(targetClass, "l", object : de.robv.android.xposed.XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    VGodEyeLogger.log("🚨 [INTERROGATION] p.v9e0.l() CALLED! 🚨")

                    // Dump all the arguments being passed into the method
                    param.args.forEachIndexed { index, arg ->
                        VGodEyeLogger.log("  -> ARG[$index]: ${arg?.javaClass?.name} = $arg")
                    }
                } catch (e: Exception) {
                    VGodEyeLogger.log("💥 Interrogation Before-Hook Error: ${e.message}")
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    // See what the method spits out after it's done chewing on our honeypot map
                    val result = param.result
                    VGodEyeLogger.log("  <- RETURNS: ${result?.javaClass?.name} = $result")
                    VGodEyeLogger.log("-----------------------------------------")
                } catch (e: Exception) {
                    // Silent catch
                }
            }
        })
    } else {
        VGodEyeLogger.log("⚠️ Class p.v9e0 not found! Did the APK version change?")
    }
}
val adMethodsLogged = mutableSetOf<String>()

/**
 * REWRITTEN: PROTOBUF SONAR
 * Uses DexKit to find every class that acts like a Protobuf message.
 */
fun SpotifyHook.InstallProtobufSonar() {
    VLogger.log("=== PROTOBUF SONAR (DEXKIT EDITION) ===")

    try {
        @Suppress("UNCHECKED_CAST")
        val methods = (::protobufMessageFingerprint as KProperty0<FindMethodListFunc>).dexMethodList

        VLogger.log("🌊 [PROTO-SONAR] DexKit found ${methods.size} methods matching the fingerprint!")
        if (methods.isEmpty()) {
            VLogger.log("❌ [PROTO-SONAR] 0 methods found. Fingerprint failed or DexKit bridge is closed.")
            return
        }

        var hooksPlaced = 0
        methods.forEach { dexMethod ->
            val methodMember = dexMethod.toMember() ?: run {
                VLogger.log("⚠️ [PROTO-SONAR] Failed to convert DexMethod to Java Member: ${dexMethod.name}")
                return@forEach
            }

            XposedBridge.hookMethod(methodMember, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // Removed the `name == "toByteArray"` check because ProGuard obfuscates the name.
                    // If it returns a ByteArray, we assume it's the serialization method.
                    val resultBytes = param.result as? ByteArray
                    if (resultBytes != null) {
                        val size = resultBytes.size
                        if (size >= 25) {
                            VLogger.log("🌊 [PROTO-DUMP] Class: ${param.thisObject?.javaClass?.name} | Method: ${methodMember.name} | Size: $size bytes\n${getCleanTrace(4)}")
                        }
                    }
                }

                override fun beforeHookedMethod(param: MethodHookParam) {
                    // Removed `name == "writeTo"` check.
                    // Instead, log if the targeted DexKit method takes arguments (like an OutputStream).
                    if (param.args.isNotEmpty()) {
                        VLogger.log("🌊 [PROTO-STREAM] Class: ${param.thisObject?.javaClass?.name} called ${methodMember.name}(...)\n${getCleanTrace(4)}")
                    }
                }
            })
            hooksPlaced++
        }
        VLogger.log("🌊 [PROTO-SONAR] Successfully placed $hooksPlaced hooks.")

    } catch (e: Exception) {
        VLogger.log("❌ [PROTO-SONAR] CRASH during DexKit resolution:\n${e.stackTraceToString()}")
    }
}

/**
 * REWRITTEN: ADS HONEYPOT
 * Finds the ProductStateImpl even if it's renamed to 'a.b.c.v1'
 */
fun SpotifyHook.InstallAdsHoneypot() {
    VLogger.log("=== ADS HONEYPOT ===")

    val psClazzResult = runCatching {
        @Suppress("UNCHECKED_CAST")
        (::productStateImplFingerprint as KProperty0<FindClassFunc>).clazz
    }

    psClazzResult.onFailure {
        VLogger.log("❌ [HONEYPOT] DexKit Exception resolving ProductStateImpl:\n${it.stackTraceToString()}")
    }

    val psClazz = psClazzResult.getOrNull()
    if (psClazz == null) {
        VLogger.log("⚠️ [HONEYPOT] Fingerprint resolved to NULL. Check your DexKit fingerprint logic.")
        return
    }

    VLogger.log("🍯 [HONEYPOT] Armed on Class: ${psClazz.name}")

    try {
        var debugHookCount = 0
        XposedBridge.hookAllMethods(psClazz, "get", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args.firstOrNull() as? String ?: return
                val value = param.result?.toString()?.lowercase() ?: return

                // PROOF OF LIFE: Log the first 5 intercepted keys just to prove the hook works
                if (debugHookCount < 5) {
                    VLogger.log("🍯 [HONEYPOT DEBUG] Intercepted get(\"$key\") -> $value")
                    debugHookCount++
                }

                if (key == "ads" && (value == "true" || value == "1")) {
                    val dossier = StringBuilder().apply {
                        append("\n🎯 [HONEYPOT TRIGGERED] 'ads=true' query caught!\n")
                        append("🔎 --- JADX DOSSIER ---\n")
                        append(getCleanTrace(8))
                        append("\n--------------------------")
                    }
                    VLogger.log(dossier.toString())
                }
            }
        })
        VLogger.log("🍯 [HONEYPOT] Successfully hooked 'get' methods!")
    } catch (e: Exception) {
        VLogger.log("❌ [HONEYPOT] Failed to hook 'get' (Method might be obfuscated?):\n${e.stackTraceToString()}")
    }
}

/**
 * REWRITTEN: AD ENGINE DRAGNET
 * Brute-force hooks classes identified as Ad-related by DexKit
 */
fun SpotifyHook.InstallAdEngineDragnet() {
    VLogger.log("=== AD ENGINE DRAGNET ===")

    val targets = mutableSetOf<Class<*>>() // Use Set to avoid duplicates

    // 1. DexKit Resolution
    @Suppress("UNCHECKED_CAST")
    runCatching { (::adManagerFingerprint as KProperty0<FindClassFunc>).clazz }.onSuccess { targets.add(it) }
    @Suppress("UNCHECKED_CAST")
    runCatching { (::adActivityFingerprint as KProperty0<FindClassFunc>).clazz }.onSuccess { targets.add(it) }

    // 2. HARDCODED FALLBACKS (If DexKit fails, these will block the ads)
    val knownAdClasses = listOf(
        "com.spotify.mobile.android.video.ads.AdActivity",
        "com.spotify.adsdisplay.display.AdActivity",
        "com.spotify.interstitial.display.InterstitialActivity",
        "com.spotify.ads.ui.AdActivity"
    )

    knownAdClasses.forEach { className ->
        val clazz = XposedHelpers.findClassIfExists(className, classLoader)
        if (clazz != null) {
            targets.add(clazz)
            VLogger.log("✅ [DRAGNET] Found Hardcoded Target: $className")
        }
    }

    if (targets.isEmpty()) {
        VLogger.log("⚠️ [AD DRAGNET] No targets found at all! Ads might leak.")
        return
    }

    var hooksPlaced = 0
    targets.forEach { clazz ->
        clazz.declaredMethods.forEach { method ->
            // Skip abstract methods to prevent crashes
            if (java.lang.reflect.Modifier.isAbstract(method.modifiers)) return@forEach

            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // If it's an Activity lifecycle method, kill the Activity
                        if (method.name == "onCreate" || method.name == "onResume") {
                            VLogger.log("💥 [DRAGNET] Killed Ad Activity: ${clazz.name}")
                            (param.thisObject as? android.app.Activity)?.finish()
                            param.result = null
                        }
                    }
                })
                hooksPlaced++
            } catch (e: Exception) {}
        }
    }
    VLogger.log("🛡️ [AD DRAGNET] Total Dynamic Hooks Placed: $hooksPlaced")
}

// Helper to filter out Xposed and system noise from logs
private fun getCleanTrace(limit: Int): String {
    return Thread.currentThread().stackTrace
        .asSequence()
        .filter { element ->
            val c = element.className
            !c.contains("Xposed") && !c.contains("chsbuffer") &&
                    !c.startsWith("java.") && !c.startsWith("android.") &&
                    !c.contains("com.google.protobuf") && !c.contains("dalvik.")
        }
        .take(limit)
        .joinToString("\n") { "    -> ${it.className}.${it.methodName} (Line: ${it.lineNumber})" }
}

private fun ByteArray.toHexDump(): String {
    return joinToString(" ") { String.format("%02X", it) }
}

private fun ByteArray.toHexDumpShort(): String {
    return joinToString(" ") { String.format("%02X", it) }
}

fun SpotifyHook.InstallAdSonar() {
    VLogger.log("=== V'S AD SONAR: DEEP WIRETAP ACTIVE ===")

    // 1. THE NETWORK DRAGNET (Cosmos Router)
    // We want to see EXACTLY what endpoints they hit to fetch ads, and what the payloads look like.
    val routerClass = XposedHelpers.findClassIfExists("com.spotify.cosmos.router.Router", classLoader)
    routerClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "resolve", object : de.robv.android.xposed.XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val request = param.args[0] ?: return
                val uri = request.callMethodOrNull("getUri") as? String ?: return

                val lowerUri = uri.lowercase()
                if (lowerUri.contains("ad") || lowerUri.contains("sponsor") || lowerUri.contains("esperanto")) {
                    // Filter out legit tracks that just happen to have "ad" in the title (like "Radiohead")
                    if (lowerUri.contains("hm://ad") || lowerUri.contains("/ads/") || lowerUri.contains("spclient")) {
                        VLogger.log("-----------------------------------------")
                        VLogger.log("🚨 [AD-SONAR: NETWORK] Outbound Ad Request Detected!")
                        VLogger.log("🌐 URI: $uri")
                        val action = request.callMethodOrNull("getAction") as? String
                        VLogger.log("🛠️ METHOD: $action")

                        val body = request.callMethodOrNull("getBody") as? ByteArray
                        if (body != null && body.isNotEmpty()) {
                            val bodyStr = String(body, Charsets.UTF_8)
                            if (bodyStr.contains("{")) {
                                VLogger.log("📦 JSON PAYLOAD: $bodyStr")
                            } else {
                                VLogger.log("🧩 BINARY/PROTO PAYLOAD (Size: ${body.size})")
                            }
                        }
                        VLogger.log("-----------------------------------------")
                    }
                }
            }

            // Try to catch the response if Cosmos resolves it synchronously or returns a readable object
            override fun afterHookedMethod(param: MethodHookParam) {
                val request = param.args[0] ?: return
                val uri = request.callMethodOrNull("getUri") as? String ?: return
                val response = param.result

                if (uri.lowercase().contains("hm://ad") && response != null) {
                    val status = response.callMethodOrNull("getStatus") ?: "UNKNOWN"
                    VLogger.log("📥 [AD-SONAR: RESPONSE] URI: $uri | Status: $status")
                }
            }
        })
    }

    // 2. THE METADATA DRAGNET (Track level)
    // When an ad is actually queued up to play, we want its raw Spotify URI and metadata.
    val trackModelClass = XposedHelpers.findClassIfExists("com.spotify.metadata.Metadata\$Track", classLoader)
    trackModelClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "getIsAd", object : de.robv.android.xposed.XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val isAd = param.result as? Boolean ?: return
                if (isAd) {
                    try {
                        val nameField = param.thisObject.javaClass.getDeclaredField("name_")
                        nameField.isAccessible = true
                        val adName = nameField.get(param.thisObject) as? String ?: "UNKNOWN"

                        val gidField = param.thisObject.javaClass.getDeclaredField("gid_")
                        gidField.isAccessible = true
                        val gidBytes = gidField.get(param.thisObject) as? ByteArray
                        val gidHex = gidBytes?.toHexString() ?: "UNKNOWN_GID"

                        VLogger.log("🎧 [AD-SONAR: PLAYER] Queued Audio Ad! Name: '$adName' | GID: $gidHex")
                    } catch (e: Exception) {
                        VLogger.log("🎧 [AD-SONAR: PLAYER] Queued Audio Ad! (Could not extract metadata: ${e.message})")
                    }
                }
            }
        })
    }

    // 3. THE STATE DRAGNET (Player State)
    // We want to know exactly what the player engine thinks it's doing when the ad starts.
    val playerStateClass = XposedHelpers.findClassIfExists("com.spotify.player.model.AutoValue_PlayerState", classLoader)
        ?: XposedHelpers.findClassIfExists("com.spotify.player.model.PlayerState", classLoader)

    playerStateClass?.let { clazz ->
        clazz.declaredMethods.filter { it.name == "track" && !java.lang.reflect.Modifier.isAbstract(it.modifiers) }.forEach { method ->
            XposedBridge.hookMethod(method, object : de.robv.android.xposed.XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val optionalTrack = param.result ?: return
                    try {
                        val isPresentMethod = optionalTrack.javaClass.getMethod("isPresent")
                        if (isPresentMethod.invoke(optionalTrack) as Boolean) {
                            val contextTrack = optionalTrack.javaClass.getMethod("get").invoke(optionalTrack)
                            val metadataMap = contextTrack.javaClass.getMethod("metadata").invoke(contextTrack) as? Map<String, String>

                            if (metadataMap != null && (metadataMap.containsKey("is_ad") || metadataMap.containsKey("ad_id"))) {
                                val uriStr = contextTrack.javaClass.getMethod("uri").invoke(contextTrack) as String
                                VLogger.log("📻 [AD-SONAR: ENGINE] Engine shifted to Ad State! URI: $uriStr")
                                VLogger.log("📻 [AD-SONAR: ENGINE] Metadata Dump: $metadataMap")
                            }
                        }
                    } catch (e: Exception) {}
                }
            })
        }
    }

    // 4. VISUAL AD DRAGNET
    val adActivities = listOf(
        "com.spotify.adsdisplay.display.VideoAdActivity",
        "com.spotify.adsdisplay.display.SponsoredSessionActivity",
        "com.spotify.adsdisplay.display.AdActivity",
        "com.spotify.interstitial.display.InterstitialActivity"
    )

    adActivities.forEach { activityName ->
        val activityClass = XposedHelpers.findClassIfExists(activityName, classLoader) ?: return@forEach
        XposedBridge.hookAllMethods(activityClass, "onCreate", object : de.robv.android.xposed.XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as android.app.Activity
                val intent = activity.intent
                VLogger.log("📺 [AD-SONAR: UI] Visual Ad Spawned: $activityName")
                VLogger.log("📺 [AD-SONAR: UI] Intent Extras: ${intent?.extras?.keySet()?.joinToString { "$it=${intent.extras?.get(it)}" }}")
            }
        })
    }
}

fun SpotifyHook.InstallOmniUrlSniffer() {
    VLogger.log("=== V'S OMNI-SNIFFER V2 (MAXIMUM FORENSICS) ARMED ===")

    val timeFormat = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    // ==========================================================
    // 1. THE BEDROCK DRAGNET (java.net.URL)
    // ==========================================================
    val urlClass = java.net.URL::class.java
    XposedBridge.hookAllConstructors(urlClass, object : de.robv.android.xposed.XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                val urlString = param.thisObject.toString()
                if (!urlString.contains("crashlytics") && !urlString.contains("metrics")) {
                    val timestamp = timeFormat.format(java.util.Date())
                    VLogger.log("[$timestamp] 🌐 [OMNI-JAVA] $urlString")
                }
            } catch (e: Exception) {}
        }
    })

    // ==========================================================
    // 2. THE COSMOS DRAGNET (Java-level Routing)
    // ==========================================================
    val routerClass = XposedHelpers.findClassIfExists("com.spotify.cosmos.router.Router", classLoader)
    routerClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "resolve", object : de.robv.android.xposed.XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val request = param.args[0] ?: return
                    val uri = request.callMethodOrNull("getUri") as? String ?: return
                    val timestamp = timeFormat.format(java.util.Date())
                    VLogger.log("[$timestamp] 🪐 [OMNI-COSMOS] $uri")
                } catch (e: Exception) {}
            }
        })
    }

    // ==========================================================
    // 3. THE EXOPLAYER DRAGNET
    // ==========================================================
    val exoClasses = listOf(
        "com.google.android.exoplayer2.ExoPlayerImpl",
        "androidx.media3.exoplayer.ExoPlayerImpl",
        "com.google.android.exoplayer2.SimpleExoPlayer"
    )

    exoClasses.forEach { className ->
        val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach
        clazz.declaredMethods.filter { it.name == "setMediaItem" || it.name == "setMediaItems" || it.name == "addMediaItem" }.forEach { method ->
            XposedBridge.hookMethod(method, object : de.robv.android.xposed.XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val mediaItem = param.args[0] ?: return
                        val mediaItemStr = mediaItem.toString()
                        val timestamp = timeFormat.format(java.util.Date())
                        VLogger.log("[$timestamp] 🎬 [OMNI-EXO] $mediaItemStr")

                        // Dump the caller trace if it's an ad
                        if (mediaItemStr.contains("ad") || mediaItemStr.contains("googleusercontent")) {
                            val trace = Thread.currentThread().stackTrace
                            for (i in 3..7) {
                                if (i < trace.size) VLogger.log("   -> Caller: ${trace[i].className}.${trace[i].methodName}")
                            }
                        }
                    } catch (e: Exception) {}
                }
            })
        }
    }

    // ==========================================================
    // 4. THE DECOMPRESSION WIRETAP (GZIP/Zlib)
    // Dumps payloads AFTER they are unzipped but BEFORE the app reads them
    // ==========================================================
    val inflaterClass = java.util.zip.Inflater::class.java
    XposedBridge.hookAllMethods(inflaterClass, "inflate", object : de.robv.android.xposed.XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                val outputBuffer = param.args[0] as? ByteArray ?: return
                val bytesRead = param.result as? Int ?: return

                if (bytesRead > 20) {
                    val snippet = String(outputBuffer.take(300).toByteArray(), Charsets.UTF_8).lowercase()
                    if (snippet.contains("artist") || snippet.contains("free") || snippet.contains("restricted") || snippet.contains("ad") || snippet.contains("shuffle")) {
                        val timestamp = timeFormat.format(java.util.Date())
                        VLogger.log("[$timestamp] 📦 [GZIP-DECOMPRESSED] Plaintext Snippet: $snippet")
                        VLogger.log("[$timestamp] 📦 [GZIP-DECOMPRESSED] Hex: ${outputBuffer.take(512).toByteArray().toHexDump()}")
                    }
                }
            } catch (e: Exception) {}
        }
    })

    // ==========================================================
    // 5. THE C++ JNI WIRETAP (Deep Native Engine Sniffer)
    // ==========================================================
    fun ByteArray.toHexDump(): String {
        return joinToString(" ") { String.format("%02X", it) }
    }

    val nativeCryptoClasses = listOf("org.conscrypt.NativeCrypto", "com.android.org.conscrypt.NativeCrypto")
    nativeCryptoClasses.forEach { className ->
        val cryptoClass = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach

        // OUTBOUND
        XposedBridge.hookAllMethods(cryptoClass, "SSL_write", object : de.robv.android.xposed.XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val buffer = param.args.find { it is ByteArray } as? ByteArray ?: return
                    val snippet = String(buffer.take(200).toByteArray(), Charsets.UTF_8).lowercase()

                    // Expanded hitlist for the Scout AI
                    if (snippet.contains("artist") || snippet.contains("capability") || snippet.contains("free") ||
                        snippet.contains("ad-logic") || snippet.contains("ads/") || snippet.contains("sponsor") ||
                        snippet.contains("googleusercontent")) {

                        val timestamp = timeFormat.format(java.util.Date())
                        VLogger.log("[$timestamp] 🧬 [C++ OUTBOUND] Text: $snippet")
                        VLogger.log("[$timestamp] 🧬 [C++ OUTBOUND] Hex: ${buffer.take(512).toByteArray().toHexDump()}")

                        // Who requested this?
                        val trace = Thread.currentThread().stackTrace
                        for (i in 3..6) {
                            if (i < trace.size) VLogger.log("   -> Triggered by: ${trace[i].className}.${trace[i].methodName}")
                        }
                    }
                } catch (e: Exception) {}
            }
        })

        // INBOUND
        XposedBridge.hookAllMethods(cryptoClass, "SSL_read", object : de.robv.android.xposed.XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val buffer = param.args.find { it is ByteArray } as? ByteArray ?: return
                    val snippet = String(buffer.take(200).toByteArray(), Charsets.UTF_8).lowercase()

                    if (snippet.contains("artist") || snippet.contains("capability") || snippet.contains("restricted") ||
                        snippet.contains("ad-logic") || snippet.contains("ads/") || snippet.contains("manifest") ||
                        snippet.contains("doubleclick")) {

                        val timestamp = timeFormat.format(java.util.Date())
                        VLogger.log("[$timestamp] 🔬 [C++ INBOUND] Text: $snippet")
                        VLogger.log("[$timestamp] 🔬 [C++ INBOUND] Hex: ${buffer.take(512).toByteArray().toHexDump()}")
                    }
                } catch (e: Exception) {}
            }
        })
    }
}

// ====================================================================
// V'S MASTER FEATURE DRAGNET V4 (THE BATCH FLUSHER)
// ====================================================================
object VFeatureDragnet {
    private const val TAG = "V-FEATURE-DRAGNET"

    // Memory Banks: One to remember what we saw, one to hold the text before writing
    private val seenFeatures = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val logQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private var isFlusherRunning = false

    fun logFeature(key: String, value: Any?, fallback: Any?, sourceType: String) {
        // Filter out normal Android OS noise
        if (key.startsWith("android.") || key.startsWith("com.google.") || key.length < 4) return

        // If it's a new key we haven't seen yet
        if (seenFeatures.add(key)) {
            val valueStr = value?.toString() ?: "NULL"
            val fallbackStr = fallback?.toString() ?: "NONE"

            // Drop it instantly into the RAM queue (Zero disk lag)
            logQueue.add("[$sourceType] '$key' | Current: [$valueStr] | Fallback: [$fallbackStr]\n")

            // Start the background flusher if it isn't running yet
            if (!isFlusherRunning) startFlusher()
        }
    }

    private fun startFlusher() {
        isFlusherRunning = true
        android.util.Log.d(TAG, "V-Dragnet Flusher Engine Started...")

        // Runs a silent background task every 3 seconds to empty the queue to the disk
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate({
            try {
                // We use the exact same Context method that successfully generated your other files!
                val context = app.revanced.extension.shared.Utils.getContext()

                if (context != null && !logQueue.isEmpty()) {
                    val file = java.io.File(context.getExternalFilesDir(null), "v_all_features_dump.txt")
                    val sb = java.lang.StringBuilder()

                    if (!file.exists()) {
                        sb.append("=== V'S BATCH-FLUSH FEATURE DUMP ===\n\n")
                    }

                    // Drain the entire memory queue into a single string block
                    var count = 0
                    while (logQueue.isNotEmpty()) {
                        sb.append(logQueue.poll())
                        count++
                    }

                    // Write it all at once to save I/O overhead
                    file.appendText(sb.toString())
                    android.util.Log.d(TAG, "Successfully flushed $count features to disk.")
                }
            } catch (e: Exception) {
                // If it fails, the text stays in the queue and tries again in 3 seconds
                android.util.Log.e(TAG, "Flush failed: ${e.message}")
            }
        }, 3, 3, java.util.concurrent.TimeUnit.SECONDS)
    }
}

fun SpotifyHook.InstallMasterFeatureDragnet() {
    VLogger.log("=== V'S MASTER FEATURE DRAGNET V4 ARMED ===")

    // ==========================================================
    // 1. THE LOCAL CACHE WIRETAP (SharedPreferences)
    // ==========================================================
    val sharedPrefsClass = XposedHelpers.findClassIfExists("android.app.SharedPreferencesImpl", classLoader)
    sharedPrefsClass?.let { clazz ->

        XposedBridge.hookAllMethods(clazz, "getString", object : de.robv.android.xposed.XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as? String ?: return
                VFeatureDragnet.logFeature(key, param.result, param.args[1], "CACHE-STR")
            }
        })

        XposedBridge.hookAllMethods(clazz, "getBoolean", object : de.robv.android.xposed.XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as? String ?: return
                VFeatureDragnet.logFeature(key, param.result, param.args[1], "CACHE-BOOL")
            }
        })

        XposedBridge.hookAllMethods(clazz, "getInt", object : de.robv.android.xposed.XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as? String ?: return
                VFeatureDragnet.logFeature(key, param.result, param.args[1], "CACHE-INT")
            }
        })
    }

    // ==========================================================
    // 2. THE JSON CONFIG PARSER (Remote Config Fallback)
    // ==========================================================
    val jsonObjectClass = XposedHelpers.findClassIfExists("org.json.JSONObject", classLoader)
    jsonObjectClass?.let { clazz ->

        XposedBridge.hookAllMethods(clazz, "optBoolean", object : de.robv.android.xposed.XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as? String ?: return
                val fallback = if (param.args.size > 1) param.args[1] else "NONE"
                VFeatureDragnet.logFeature(key, param.result, fallback, "JSON-BOOL")
            }
        })

        XposedBridge.hookAllMethods(clazz, "optString", object : de.robv.android.xposed.XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as? String ?: return
                val fallback = if (param.args.size > 1) param.args[1] else "NONE"
                VFeatureDragnet.logFeature(key, param.result, fallback, "JSON-STR")
            }
        })
    }
}

