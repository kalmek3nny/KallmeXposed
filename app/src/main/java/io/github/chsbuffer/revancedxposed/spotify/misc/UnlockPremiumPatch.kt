package io.github.chsbuffer.revancedxposed.spotify.misc

import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import app.revanced.extension.shared.Logger
import app.revanced.extension.shared.Utils
import app.revanced.extension.spotify.misc.UnlockPremiumPatch
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.callMethod
import io.github.chsbuffer.revancedxposed.callMethodOrNull
import io.github.chsbuffer.revancedxposed.findField
import io.github.chsbuffer.revancedxposed.findFirstFieldByExactType
import io.github.chsbuffer.revancedxposed.getObjectFieldOrNull
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.nio.charset.StandardCharsets
import android.content.Intent
import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
object VLogger {
    private const val TAG = "V-DEEP-CORE"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(message: String) {
        Log.d(TAG, message)
        try {
            val context = Utils.getContext() ?: return
            val extDir = context.getExternalFilesDir(null) ?: return
            val logFile = File(extDir, "v_sniffer_log.txt")

            val timestamp = dateFormat.format(Date())
            synchronized(this) {
                logFile.appendText("[$timestamp] $message\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "VLogger IO Failure: ${e.message}")
        }
    }

    fun toast(message: String) {
        try {
            val context = Utils.getContext() ?: return
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            log("[V-LOGGER] Failed to show Toast: ${e.message}")
        }
    }
}

private fun ByteArray.toHexDumpShort(): String {
    return joinToString(" ") { String.format("%02X", it) }
}

object VNativeSurgeon {
    fun patchAndVerify(libraryName: String, offset: Long, expectedOriginalHex: String, newHex: String): Boolean {
        val cleanExpected = expectedOriginalHex.replace(" ", "").uppercase()
        val cleanNew = newHex.replace(" ", "").uppercase()
        val shortOffset = "0x${offset.toString(16)}"

        VLogger.log("[V-SURGEON] 🪚 Preparing to slice $libraryName at offset $shortOffset")

        if (!isArm64()) {
            VLogger.log("[V-SURGEON] 🛑 ABORT: Not ARM64.")
            return false
        }

        val baseAddress = getModuleExecutableBase(libraryName)
        if (baseAddress == 0L) {
            VLogger.log("[V-SURGEON] ❌ ABORT: $libraryName executable base not found.")
            return false
        }

        val targetAddress = baseAddress + offset
        val patchBytes = hexStringToByteArray(cleanNew)

        // --- PRE-OP CHECK ---
        val currentBytes = readMemory(targetAddress, patchBytes.size)
        if (currentBytes == null) {
            VLogger.log("[V-SURGEON] ❌ ABORT: Could not read memory at $shortOffset.")
            VLogger.toast("V-Surgeon: Cannot read memory at $shortOffset") // 🍞 TOAST
            return false
        }

        val currentHex = currentBytes.toHexString()

        if (currentHex == cleanNew) {
            VLogger.log("[V-SURGEON] ⚡ SUCCESS: Target is already patched! ($currentHex)")
            return true
        }

        if (cleanExpected != "FILL_ME_IN" && currentHex != cleanExpected) {
            VLogger.log("[V-SURGEON] 🛑 ABORT: Offset shifted! Expected [$cleanExpected] but found [$currentHex].")
            VLogger.toast("V-Surgeon: Offset shifted at $shortOffset!") // 🍞 TOAST
            return false
        } else if (cleanExpected == "FILL_ME_IN") {
            VLogger.log("[V-SURGEON] ⚠️ UNKNOWN EXPECTED BYTES! Please update your code with found bytes: [$currentHex]")
            // We won't toast here since you intentionally left it blank to find the bytes
            return false
        }

        // --- THE INCISION ---
        if (!patchMemory(targetAddress, patchBytes)) {
            VLogger.log("[V-SURGEON] ❌ ERROR: patchMemory function failed.")
            VLogger.toast("V-Surgeon: patchMemory failed at $shortOffset") // 🍞 TOAST
            return false
        }

        // --- POST-OP VERIFICATION ---
        val verifyBytes = readMemory(targetAddress, patchBytes.size)
        val verifyHex = verifyBytes?.toHexString() ?: "READ_FAILED"

        if (verifyHex == cleanNew) {
            VLogger.log("[V-SURGEON] 🟢 VERIFIED: Injection successful at $shortOffset -> $verifyHex")
            return true
        } else {
            VLogger.log("[V-SURGEON] 🔴 FAILED: Suture didn't hold. Found: $verifyHex")
            VLogger.toast("V-Surgeon: Write blocked at $shortOffset!") // 🍞 TOAST
            return false
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    // 🗡️ The Laser Scalpel: Direct RAM Access Bypass
    private val unsafe: Any? by lazy {
        try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val f: Field = unsafeClass.getDeclaredField("theUnsafe")
            f.isAccessible = true
            f.get(null)
        } catch (e: Exception) {
            VLogger.log("[V-NATIVE] 💥 Failed to initialize sun.misc.Unsafe: ${e.message}")
            null
        }
    }

    private val getByteMethod by lazy {
        unsafe?.javaClass?.getMethod("getByte", Long::class.javaPrimitiveType)
    }

    private val putByteMethod by lazy {
        unsafe?.javaClass?.getMethod("putByte", Long::class.javaPrimitiveType, Byte::class.javaPrimitiveType)
    }

    fun readMemory(address: Long, size: Int): ByteArray? {
        if (address == 0L || size <= 0 || unsafe == null || getByteMethod == null) return null
        return try {
            val buffer = ByteArray(size)
            for (i in 0 until size) {
                buffer[i] = getByteMethod!!.invoke(unsafe, address + i) as Byte
            }
            buffer
        } catch (e: Exception) {
            VLogger.log("[V-NATIVE] 💥 Unsafe readMemory failed: ${e.message}")
            null
        }
    }

    fun patchMemory(address: Long, patch: ByteArray): Boolean {
        if (address == 0L || patch.isEmpty() || unsafe == null || putByteMethod == null) return false

        try {
            val pageSize = 4096L // Standard ARM64 page size
            val pageStart = address - (address % pageSize)
            // Ensure we calculate alignment correctly if patch crosses page boundaries
            val alignLength = ((address + patch.size - pageStart + pageSize - 1) / pageSize) * pageSize

            val PROT_RW = 3 // READ | WRITE
            val PROT_RX = 5 // READ | EXECUTE

            // 1. Force mprotect via XposedHelpers.
            // Because the .so is mapped as MAP_PRIVATE, granting it PROT_RW forces the Kernel
            // to trigger a Copy-On-Write (COW), decoupling it from the physical file safely.
            try {
                val osClass = XposedHelpers.findClass("android.system.Os", this::class.java.classLoader)
                XposedHelpers.callStaticMethod(osClass, "mprotect", pageStart, alignLength, PROT_RW)
            } catch (e: Exception) {
                // If this fails, SELinux is aggressively blocking COW on executable memory.
                VLogger.log("[V-NATIVE] 🛑 Kernel denied mprotect(RW): ${e.cause?.message ?: e.message}")
                return false
            }

            // 2. Inject bytes directly into RAM
            for (i in patch.indices) {
                putByteMethod!!.invoke(unsafe, address + i, patch[i])
            }

            // 3. Restore RX
            try {
                val osClass = XposedHelpers.findClass("android.system.Os", this::class.java.classLoader)
                XposedHelpers.callStaticMethod(osClass, "mprotect", pageStart, alignLength, PROT_RX)
            } catch (e: Exception) {
                VLogger.log("[V-NATIVE] ⚠️ Failed to restore RX lock. Execution might fault.")
            }

            VLogger.log("[V-NATIVE] 💉 SUCCESS: Spliced ${patch.size} bytes at 0x${address.toString(16)}")
            return true

        } catch (e: Exception) {
            VLogger.log("[V-NATIVE] 💥 Patch logic failed: ${e.message}")
            return false
        }
    }

    fun isArm64(): Boolean {
        return Build.SUPPORTED_ABIS.contains("arm64-v8a")
    }

    fun getModuleExecutableBase(libName: String): Long {
        try {
            val maps = File("/proc/self/maps").readLines()
            for (line in maps) {
                if (line.contains(libName) && line.contains("r-xp")) {
                    val base = line.substringBefore("-").toLong(16)
                    VLogger.log("[V-NATIVE] 📍 Found executable base for $libName at 0x${base.toString(16)}")
                    return base
                }
            }
        } catch (e: Exception) {
            VLogger.log("[V-NATIVE] 💥 Maps parsing failed: ${e.message}")
        }
        return 0L
    }
}

// ==========================================
// === NATIVE UTILS (ATOMIC SHADOW MAP) =====
// ==========================================
object NativeUtils {

    private val unsafe: Any? by lazy {
        try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val f: Field = unsafeClass.getDeclaredField("theUnsafe")
            f.isAccessible = true
            f.get(null)
        } catch (e: Exception) { null }
    }

    fun readOriginalBytes(address: Long, size: Int): String {
        // Reference VNativeSurgeon specifically
        val bytes = VNativeSurgeon.readMemory(address, size) ?: return "READ_FAILED"
        return bytes.toHexString()
    }

    // Standard ARM64 NOP (No Operation)
    const val NOP = "1F2003D5"
    // Standard ARM64 RET (Return)
    const val RET = "C0035FD6"

    private val getByteMethod by lazy {
        unsafe?.javaClass?.getMethod("getByte", Long::class.javaPrimitiveType)
    }

    fun atomicShadowPatch(address: Long, patch: ByteArray): Boolean {
        if (address == 0L || patch.isEmpty() || unsafe == null || getByteMethod == null) return false

        try {
            val pageSize = 4096L // Standard ARM64 page size
            val pageStart = address - (address % pageSize)
            val patchOffset = (address - pageStart).toInt()

            // --- 1. READ LIVE PAGE ---
            // W^X does not block reads. We clone the live executing page seamlessly.
            val pageBytes = ByteArray(pageSize.toInt())
            for (i in 0 until pageSize.toInt()) {
                pageBytes[i] = getByteMethod!!.invoke(unsafe, pageStart + i) as Byte
            }

            // --- 2. APPLY SURGERY LOCALLY ---
            for (i in patch.indices) {
                pageBytes[patchOffset + i] = patch[i]
            }

            // --- 3. CREATE SHADOW FILE ---
            // This satisfies SELinux's strict requirement that executable memory must be file-backed.
            val context = Utils.getContext() ?: return false
            val shadowFile = File(context.cacheDir, "v_shadow_${address.toString(16)}.bin")
            shadowFile.writeBytes(pageBytes)

            val fis = java.io.FileInputStream(shadowFile)
            val fd = fis.fd

            val PROT_RX = OsConstants.PROT_READ or OsConstants.PROT_EXEC
            val MAP_PRIVATE = OsConstants.MAP_PRIVATE
            val MAP_FIXED = 0x10 // Magic flag: Force overwrite the live memory page

            // --- 4. THE ATOMIC PTE SWAP ---
            // The Kernel instantly swaps the Page Table Entry to point to our file.
            // It completely bypasses W^X because we are mapping it as PROT_RX directly.
            val mappedAddr = Os.mmap(
                pageStart,
                pageSize,
                PROT_RX,
                MAP_PRIVATE or MAP_FIXED,
                fd,
                0
            )

            fis.close()

            if (mappedAddr == pageStart) {
                return true
            } else {
                VLogger.log("[V-ATOMIC] ❌ Kernel shifted MAP_FIXED address to 0x${mappedAddr.toString(16)}")
                return false
            }

        } catch (e: Exception) {
            VLogger.log("[V-ATOMIC] 💥 Atomic shadow patch failed: ${e.message}")
            return false
        }
    }
}

/**
 * V'S GHOST BUFFER DREDGER (SELINUX BYPASS)
 * Forges a native ByteBuffer over raw RAM to bypass /proc/self/mem restrictions.
 */
fun performStringManglingSurgeon() {
    VLogger.log("[V-ATOMIC] 👻 Initiating Ghost Buffer Dredger (SELinux Bypass)...")
    var mangledCount = 0
    var controlGroupFound = 0

    val adStrings = listOf(
        // 🎯 THE ESSENTIAL KILLSHOTS (Cosmos Endpoints)
        // Mangling these causes the internal router to return 404 Not Found
        // to the ad engine, which it handles gracefully without crashing.
        "core-ads",
        "video-ads",
        "audio-ads",

        // 🎯 THE CONFIG KILLSHOT
        // From your C++ dump. If we mangle this, the app asks the server for "Xax_ads".
        // The server won't recognize it and will return null, giving you infinite skips.
        "max_ads"
    )

    // 🗡️ Helper to forge the Ghost Buffer
    fun createGhostBuffer(address: Long, size: Int): ByteBuffer? {
        return try {
            val bb = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
            de.robv.android.xposed.XposedHelpers.setLongField(bb, "address", address)
            de.robv.android.xposed.XposedHelpers.setIntField(bb, "capacity", size)
            de.robv.android.xposed.XposedHelpers.setIntField(bb, "limit", size)
            bb
        } catch (e: Exception) {
            null
        }
    }

    try {
        val maps = File("/proc/self/maps").readLines()
        val readableMaps = maps.filter {
            (it.contains("liborbit") || it.contains("libspotify") || it.contains("libesperanto")) &&
                    it.contains(".so") &&
                    (it.contains("r--p") || it.contains("r-xp") || it.contains("rw-p"))
        }

        VLogger.log("[V-ATOMIC] Dredging ${readableMaps.size} native memory segments via Ghost Buffers...")

        for (mapLine in readableMaps) {
            try {
                val addressRange = mapLine.substringBefore(" ")
                val startAddr = addressRange.substringBefore("-").toLong(16)
                val endAddr = addressRange.substringAfter("-").toLong(16)
                val size = (endAddr - startAddr).toInt()
                val libName = mapLine.substringAfterLast("/")

                if (size > 50_000_000 || size <= 0) continue // Skip bad mappings

                // Forge the buffer and rip the memory instantly into Java
                val ghostBuffer = createGhostBuffer(startAddr, size) ?: continue
                val buffer = ByteArray(size)

                try {
                    // This acts as a native C memcpy. Instant bulk read, zero OS file checks!
                    ghostBuffer.get(buffer)
                } catch (e: Exception) {
                    VLogger.log("[V-ATOMIC] ⚠️ Page fault reading $libName at 0x${startAddr.toString(16)}. Skipping.")
                    continue
                }

                // Scan the ripped memory
                for (targetString in adStrings) {
                    val targetBytes = targetString.toByteArray()
                    var index = indexOfSubArray(buffer, targetBytes, 0)

                    while (index != -1) {
                        val exactStringAddress = startAddr + index

                        if (targetString == "spotify") {
                            controlGroupFound++
                        } else {
                            VLogger.log("[V-ATOMIC] 🎯 FOUND AD STRING: '$targetString' in $libName @ 0x${exactStringAddress.toString(16)}")

                            val patchByte = byteArrayOf(0x58.toByte()) // 'X'
                            if (NativeUtils.atomicShadowPatch(exactStringAddress, patchByte)) {
                                mangledCount++
                                VLogger.log("[V-ATOMIC] 💉 Mangled successfully!")
                            }
                        }
                        index = indexOfSubArray(buffer, targetBytes, index + 1)
                    }
                }
            } catch (e: Exception) {
                // Skip errors on specific blocks
            }
        }

        VLogger.log("[V-ATOMIC] Scan complete. Control Group ('spotify') found $controlGroupFound times.")

        if (mangledCount > 0) {
            VLogger.toast("V-Surgeon: Mangled $mangledCount strings! 👑")
        } else if (controlGroupFound > 0) {
            VLogger.toast("V-Surgeon: Bypass successful, but Ads are hidden!")
            VLogger.log("[V-ATOMIC] ⚠️ We read the memory successfully, but no ad strings were found.")
        } else {
            VLogger.toast("V-Surgeon: Ghost Buffer Failed.")
            VLogger.log("[V-ATOMIC] ❌ Memory is completely randomized or zeroed out.")
        }

    } catch (e: Exception) {
        VLogger.log("[V-ATOMIC] ❌ Critical Error: ${e.stackTraceToString()}")
    }
}

private fun indexOfSubArray(outerArray: ByteArray, smallerArray: ByteArray, startIndex: Int): Int {
    if (smallerArray.isEmpty()) return -1
    for (i in startIndex..outerArray.size - smallerArray.size) {
        var found = true
        for (j in smallerArray.indices) {
            if (outerArray[i + j] != smallerArray[j]) {
                found = false
                break
            }
        }
        if (found) return i
    }
    return -1
}
// ====================================================================
// V'S ANONYMOUS PAGE SWAP (THE ULTIMATE KNOX BYPASS)
// ====================================================================
fun SpotifyHook.InstallAnonymousPageMangler() {
    android.util.Log.e("V-MANGLER", "🧨 V: INITIATING ANONYMOUS PAGE SWAP 🧨")

    Thread {
        try {
            // Give Spotify 4 seconds to fully boot and unpack its libraries
            Thread.sleep(4000)

            val target = "core-ads".toByteArray()
            val replacement = "Xore-ads".toByteArray()

            // Unsafe RAM Access
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val f: Field = unsafeClass.getDeclaredField("theUnsafe")
            f.isAccessible = true
            val unsafe = f.get(null)
            val getByteMethod = unsafe.javaClass.getMethod("getByte", Long::class.javaPrimitiveType)
            val putByteMethod = unsafe.javaClass.getMethod("putByte", Long::class.javaPrimitiveType, Byte::class.javaPrimitiveType)

            // Find the core native libraries
            val maps = File("/proc/self/maps").readLines().filter {
                (it.contains("orbit") || it.contains("spotify") || it.contains("esperanto")) &&
                        it.contains(".so") && (it.contains("r--p") || it.contains("r-xp"))
            }

            var mangled = 0

            for (mapLine in maps) {
                val addressRange = mapLine.substringBefore(" ")
                val startAddr = addressRange.substringBefore("-").toLong(16)
                val endAddr = addressRange.substringAfter("-").toLong(16)
                val size = (endAddr - startAddr).toInt()

                if (size > 50_000_000 || size <= 0) continue

                // 1. Safe Read: Clone the live memory into a buffer
                val buffer = ByteArray(size)
                try {
                    for (i in 0 until size) {
                        buffer[i] = getByteMethod.invoke(unsafe, startAddr + i) as Byte
                    }
                } catch (e: Exception) { continue } // Skip unreadable pages silently

                // 2. Hunt for the exact string "core-ads"
                var index = 0
                while (index <= buffer.size - target.size) {
                    var match = true
                    for (j in target.indices) {
                        if (buffer[index + j] != target[j]) { match = false; break }
                    }

                    if (match) {
                        val exactAddress = startAddr + index
                        android.util.Log.e("V-MANGLER", "🎯 FOUND 'core-ads' @ 0x${exactAddress.toString(16)}")

                        // --- 3. THE ANONYMOUS PAGE SWAP ---
                        val PAGE_SIZE = 4096L
                        val pageStart = exactAddress - (exactAddress % PAGE_SIZE)

                        // Backup the entire original memory page
                        val pageBackup = ByteArray(PAGE_SIZE.toInt())
                        for (i in 0 until PAGE_SIZE.toInt()) {
                            pageBackup[i] = getByteMethod.invoke(unsafe, pageStart + i) as Byte
                        }

                        // Mangle our backup in safe Java space
                        val offsetInPage = (exactAddress - pageStart).toInt()
                        for (j in replacement.indices) {
                            pageBackup[offsetInPage + j] = replacement[j]
                        }

                        // Forge an invalid FileDescriptor (-1) required for Anonymous RAM mappings
                        val fd = java.io.FileDescriptor()
                        val descriptorField = fd.javaClass.getDeclaredField("descriptor")
                        descriptorField.isAccessible = true
                        descriptorField.set(fd, -1)

                        val MAP_ANONYMOUS = 0x20 // Standard Linux flag for pure RAM mapping

                        // 💥 VIOLENT OVERWRITE: Rip out the file-backed page and slam our RAM page into the exact same slot
                        val mappedAddr = Os.mmap(
                            pageStart,
                            PAGE_SIZE,
                            OsConstants.PROT_READ or OsConstants.PROT_WRITE,
                            OsConstants.MAP_PRIVATE or OsConstants.MAP_FIXED or MAP_ANONYMOUS,
                            fd,
                            0
                        )

                        if (mappedAddr == pageStart) {
                            // Inject our mangled backup into the new stealth page
                            for (i in 0 until PAGE_SIZE.toInt()) {
                                putByteMethod.invoke(unsafe, pageStart + i, pageBackup[i])
                            }

// Force explicit primitive types using strict Java reflection
                            val mprotectMethod = android.system.Os::class.java.getDeclaredMethod(
                                "mprotect",
                                Long::class.javaPrimitiveType,
                                Long::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType
                            )
                            mprotectMethod.isAccessible = true
                            mprotectMethod.invoke(null, pageStart, PAGE_SIZE, android.system.OsConstants.PROT_READ)

                            mangled++
                            android.util.Log.e("V-MANGLER", "🩸 'core-ads' brutally mangled to 'Xore-ads' (Hardware Trap Bypassed!)")
                        } else {
                            android.util.Log.e("V-MANGLER", "❌ Kernel rejected anonymous swap.")
                        }
                        index += target.size
                    } else {
                        index++
                    }
                }
            }

            android.util.Log.e("V-MANGLER", "🏁 Total 'core-ads' strings slaughtered: $mangled")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(app, "V: Mangled $mangled 'core-ads' strings! 🩸", android.widget.Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            android.util.Log.e("V-MANGLER", "💥 Fatal Error: ${e.message}")
        }
    }.start()
}

object ProtobufAttributeScanner {
    private val targetKeys = listOf(
        "ads", "player-license", "shuffle", "on-demand", "streaming",
        "type", "catalogue", "high-bitrate", "financial-product",
        "audio-quality", "streaming-quality", "jam-social-session"
    )

    fun scan(payload: ByteArray) {
        for (key in targetKeys) {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val index = indexOf(payload, keyBytes)

            if (index != -1) {
                VLogger.log("[V-PROTO-SCANNER] Found Attribute: '$key' at offset ${String.format("%04X", index)}")

                val contextSize = 20.coerceAtMost(payload.size - (index + keyBytes.size))
                val context = payload.sliceArray((index + keyBytes.size) until (index + keyBytes.size + contextSize))

                VLogger.log("[V-PROTO-SCANNER] Context Hex: ${context.toHexDumpShort()}")
            }
        }
    }

    private fun indexOf(outer: ByteArray, target: ByteArray): Int {
        if (target.isEmpty()) return -1
        for (i in 0..outer.size - target.size) {
            var found = true
            for (j in target.indices) {
                if (outer[i + j] != target[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    private fun ByteArray.toHexDumpShort(): String {
        return joinToString(" ") { String.format("%02X", it) }
    }
}

fun SpotifyHook.InstallUIPlayDeflection() {
    VLogger.log("=== UI Play Deflection Activated ===")

    val playCommandClass = XposedHelpers.findClassIfExists("com.spotify.player.model.command.PlayCommand", classLoader)

    playCommandClass?.let { clazz ->
        XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    for (i in param.args.indices) {
                        val arg = param.args[i] as? String ?: continue

                        if (arg.startsWith("spotify:track:") && !arg.contains("station")) {
                            val spoofedUri = arg.replace("spotify:track:", "spotify:station:track:")
                            param.args[i] = spoofedUri
                            VLogger.log("[UI-DEFLECTION] Rewrote '$arg' to '$spoofedUri'")
                        }
                    }
                } catch (e: Exception) {}
            }
        })
    }
}


fun SpotifyHook.InstallWorkingAdsRemoval() {
    VLogger.log("=== Ads Removal Activated ===")

    ::homeStructureGetSectionsFingerprint.hookMethod {
        after { param ->
            try {
                val sections = param.result ?: return@after
                // Keep the modifiable flag flip just in case the app checks it before our proxy intercepts
                sections.javaClass.findFirstFieldByExactType(Boolean::class.java).set(sections, true)

                // Route the original list through our stealth proxy and hand it back to the app
                param.result = UnlockPremiumPatch.filterHomeSections(sections as List<*>)
            } catch (e: Exception) {
                VLogger.log("[HOME-CLEANER] Failed to filter list: ${e.message}")
            }
        }
    }

    ::browseStructureGetSectionsFingerprint.hookMethod {
        after { param ->
            try {
                val sections = param.result ?: return@after
                sections.javaClass.findFirstFieldByExactType(Boolean::class.java).set(sections, true)

                // Route the original list through our stealth proxy and hand it back to the app
                param.result = UnlockPremiumPatch.filterBrowseSections(sections as List<*>)
            } catch (e: Exception) {
                VLogger.log("[BROWSE-CLEANER] Failed to filter list: ${e.message}")
            }
        }
    }
}

fun SpotifyHook.InstallWorkingPendragonFix() {
    VLogger.log("=== Pendragon Fix Activated ===")
    val replaceFetchRequestSingleWithError = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val result = param.result ?: return
            if (!result.javaClass.name.endsWith("SingleOnErrorReturn")) return

            try {
                val funcField = result.javaClass.declaredFields.find { it.name == "b" || it.type.name.contains("Function") }
                funcField?.isAccessible = true
                val fallbackItem = funcField?.get(result)

                val singleClass = XposedHelpers.findClass("io.reactivex.rxjava3.core.Single", classLoader)

                if (fallbackItem != null) {
                    val justMethod = XposedHelpers.findMethodExact(singleClass, "just", Object::class.java)
                    param.result = justMethod.invoke(null, fallbackItem)
                } else {
                    val neverMethod = XposedHelpers.findMethodExact(singleClass, "never", *emptyArray<Class<*>>())
                    param.result = neverMethod.invoke(null)
                    VLogger.log("[V-PENDRAGON] Ad request blocked.")
                }
            } catch (e: Exception) {}
        }
    }

    runCatching { ::pendragonJsonFetchMessageRequestFingerprint.hookMethod(replaceFetchRequestSingleWithError) }
    runCatching { ::pendragonJsonFetchMessageListRequestFingerprint.hookMethod(replaceFetchRequestSingleWithError) }
}

fun SpotifyHook.InstallAdChoke() {
    VLogger.log("=== Ad Choke Activated ===")

    val routerClass = XposedHelpers.findClassIfExists("com.spotify.cosmos.router.Router", classLoader)
    routerClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "resolve", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val request = param.args[0] ?: return
                val uri = request.callMethodOrNull("getUri") as? String ?: return

                if (uri.contains("hm://ad-logic", ignoreCase = true) ||
                    uri.contains("hm://ads", ignoreCase = true) ||
                    uri.contains("hm://slate") ||
                    uri.contains("hm://creative") ||
                    uri.contains("hm://formats") ||
                    uri.contains("hm://in-app-messaging") ||
                    uri.contains("hm://ad-state", ignoreCase = true)) {

                    VLogger.log("[AD-CHOKE] Blocked request to: $uri")
                    param.result = null
                }
            }
        })
    }
}

fun SpotifyHook.InstallAdAutoSkip() {
    VLogger.log("=== Ad Auto-Skip Activated ===")

    val metadataClass = XposedHelpers.findClassIfExists("com.spotify.metadata.Metadata\$Track", classLoader)
    metadataClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "getIsAd", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val isAd = param.result as? Boolean ?: return

                if (isAd) {
                    VLogger.log("[AUTO-SKIP] Ad detected in metadata")
                    param.result = false
                }
            }
        })
    }
}

fun SpotifyHook.InstallSlateModalAssassin() {
    VLogger.log("=== Slate Modal Blocking Activated ===")

    val dialogClass = XposedHelpers.findClassIfExists("android.app.Dialog", classLoader)

    dialogClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "show", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val dialog = param.thisObject
                val dialogClassname = dialog.javaClass.name.lowercase()

                if (dialogClassname.contains("slate") ||
                    dialogClassname.contains("promo") ||
                    dialogClassname.contains("interstitial") ||
                    dialogClassname.contains("ad") ||
                    dialogClassname.contains("marketing") ||
                    dialogClassname.contains("messaging") ||
                    dialogClassname.contains("pendragon")) {

                    VLogger.log("[SLATE-ASSASSIN] Blocked dialog: ${dialog.javaClass.name}")
                    param.result = null

                    try {
                        dialog.callMethod("dismiss")
                    } catch (e: Exception) {}
                }
            }
        })
    }
}

fun SpotifyHook.InstallNuclearAdScrubber() {
    VLogger.log("=== Nuclear Ad Scrubber Activated ===")

    val trackModelClass = XposedHelpers.findClassIfExists("com.spotify.metadata.Metadata\$Track", classLoader)
    trackModelClass?.let { clazz ->
        try {
            XposedBridge.hookAllMethods(clazz, "getIsAd", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val isAd = param.result as? Boolean ?: return
                    if (isAd) {
                        VLogger.log("[NUCLEAR-SCRUBBER] Audio ad detected")

                        param.result = false

                        try {
                            val durationField = param.thisObject.javaClass.getDeclaredField("duration_")
                            durationField.isAccessible = true
                            durationField.set(param.thisObject, 0)

                            val fileField = param.thisObject.javaClass.getDeclaredField("file_")
                            fileField.isAccessible = true
                            fileField.set(param.thisObject, java.util.Collections.emptyList<Any>())
                        } catch (e: Exception) {}
                    }
                }
            })
        } catch (e: Exception) {
            VLogger.log("[NUCLEAR-SCRUBBER] Failed to hook getIsAd: ${e.message}")
        }
    }

    val playerStateClass = XposedHelpers.findClassIfExists("com.spotify.player.model.AutoValue_PlayerState", classLoader)
        ?: XposedHelpers.findClassIfExists("com.spotify.player.model.PlayerState", classLoader)

    if (playerStateClass != null) {
        var hookPlaced = false

        playerStateClass.declaredMethods.forEach { method ->
            if (method.name == "track" && !java.lang.reflect.Modifier.isAbstract(method.modifiers)) {
                try {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val optionalTrack = param.result ?: return

                            try {
                                val isPresentMethod = optionalTrack.javaClass.getMethod("isPresent")
                                val isPresent = isPresentMethod.invoke(optionalTrack) as Boolean

                                if (isPresent) {
                                    val getMethod = optionalTrack.javaClass.getMethod("get")
                                    val contextTrack = getMethod.invoke(optionalTrack)

                                    val metadataMethod = contextTrack.javaClass.getMethod("metadata")
                                    val metadataMap = metadataMethod.invoke(contextTrack) as? Map<String, String>

                                    if (metadataMap != null && (metadataMap.containsKey("is_ad") || metadataMap.containsKey("ad_id"))) {
                                        VLogger.log("[AUTO-SKIP] Ad loaded in player state")

                                        val uriMethod = contextTrack.javaClass.getMethod("uri")
                                        val uriStr = uriMethod.invoke(contextTrack) as String
                                        VLogger.log("Target URI: $uriStr")
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                    })
                    hookPlaced = true
                } catch (e: Exception) {}
            }
        }

        if (hookPlaced) {
            VLogger.log("[AUTO-SKIP] Hook placed successfully")
        }
    }

    val promoCardClass = XposedHelpers.findClassIfExists("com.spotify.interstitial.display.InterstitialActivity", classLoader)
    promoCardClass?.let { clazz ->
        try {
            XposedBridge.hookAllMethods(clazz, "onCreate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    VLogger.log("[VISUAL-ASSASSIN] Blocked interstitial activity")
                    val activity = param.thisObject as android.app.Activity
                    activity.finish()
                    param.result = null
                }
            })
        } catch (e: Exception) {
            VLogger.log("[VISUAL-ASSASSIN] Failed: ${e.message}")
        }
    }
}

fun SpotifyHook.InstallPlayabilityForcer() {
    VLogger.log("=== Playability Forcer Activated ===")

    val trackClass = XposedHelpers.findClassIfExists("com.spotify.metadata.Metadata\$Track", classLoader)

    trackClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "getIsPlayable", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = true
            }
        })

        XposedBridge.hookAllMethods(clazz, "getIsRestricted", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = false
            }
        })
    }

    val trackRepClasses = listOf(
        "com.spotify.nowplaying.models.TrackRepresentation\$Normal",
        "com.spotify.nowplaying.engine.models.NowPlayingState\$Track",
        "com.spotify.music.features.track.playability.Playability"
    )

    trackRepClasses.forEach { className ->
        val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach

        clazz.declaredMethods.filter { it.returnType == Boolean::class.java }.forEach { method ->
            val mName = method.name.lowercase()

            if (mName.contains("playable")) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) { param.result = true }
                })
            } else if (mName.contains("restricted") || mName.contains("premiumonly") || mName.contains("locked")) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) { param.result = false }
                })
            }
        }
    }
}

fun SpotifyHook.InstallVisualAdSniper() {
    VLogger.log("=== Visual Ad Sniper Activated ===")

    val adActivities = listOf(
        "com.spotify.adsdisplay.display.VideoAdActivity",
        "com.spotify.adsdisplay.display.SponsoredSessionActivity",
        "com.spotify.adsdisplay.display.AdActivity",
        "com.spotify.interstitial.display.InterstitialActivity",
        "com.spotify.adsdisplay.browser.inapp.InAppBrowserActivity"
    )

    adActivities.forEach { activityName ->
        val clazz = XposedHelpers.findClassIfExists(activityName, classLoader) ?: return@forEach

        XposedBridge.hookAllMethods(clazz, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                VLogger.log("[AD-SNIPER] Blocked: $activityName")
                val activity = param.thisObject as android.app.Activity
                activity.finish()
            }
        })
    }

    runCatching {
        val dynamicAdActivityClass = ::adActivityFingerprint.clazz
        XposedBridge.hookAllMethods(dynamicAdActivityClass, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as android.app.Activity
                VLogger.log("[DYNAMIC-SNIPER] Blocked obfuscated ad activity")
                activity.finish()
            }
        })
    }
}

fun SpotifyHook.InstallGlobalUIAssassin() {
    VLogger.log("=== Global UI Assassin Activated ===")

    val activityClass = XposedHelpers.findClassIfExists("android.app.Activity", classLoader)

    activityClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as android.app.Activity
                val className = activity.javaClass.name.lowercase()

                val intentStr = activity.intent?.toString()?.lowercase() ?: ""
                val extrasStr = activity.intent?.extras?.keySet()?.joinToString { key ->
                    "$key=${activity.intent?.extras?.get(key)}"
                }?.lowercase() ?: ""

                if (className.contains("adactivity") ||
                    className.contains("sponsor") ||
                    className.contains("interstitial") ||
                    className.contains("promo") ||
                    className.contains("inappbrowser") ||
                    intentStr.contains("ad_id") ||
                    intentStr.contains("is_ad") ||
                    extrasStr.contains("ad_id") ||
                    extrasStr.contains("sponsored")) {

                    VLogger.log("[GLOBAL-ASSASSIN] Blocked: $className")
                    activity.finish()
                }
            }
        })
    }
}

fun SpotifyHook.InstallGodsEyeTracer() {
    VLogger.log("=== God's Eye Tracer Activated ===")

    val mediaSessionClasses = listOf(
        "android.support.v4.media.session.MediaSessionCompat",
        "android.media.session.MediaSession"
    )

    mediaSessionClasses.forEach { className ->
        val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach

        clazz.declaredMethods.filter { it.name == "setMetadata" }.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val metadata = param.args[0] ?: return

                        val bundleMethod = metadata.javaClass.getMethod("getBundle")
                        val bundle = bundleMethod.invoke(metadata) as? android.os.Bundle ?: return

                        val title = bundle.getString("android.media.metadata.TITLE")?.lowercase() ?: ""
                        val album = bundle.getString("android.media.metadata.ALBUM")?.lowercase() ?: ""

                        if (title.contains("advertisement") || title.contains("spotify") || album.contains("ad")) {
                            VLogger.log("[GODS-EYE] Ad metadata detected")

                            val trace = Thread.currentThread().stackTrace
                            for (i in 3..10) {
                                if (i < trace.size) {
                                    VLogger.log("   Origin: ${trace[i].className}.${trace[i].methodName}")
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            })
        }
    }

    val exoPlayerClasses = listOf(
        "com.google.android.exoplayer2.ExoPlayerImpl",
        "androidx.media3.exoplayer.ExoPlayerImpl",
        "com.google.android.exoplayer2.SimpleExoPlayer"
    )

    exoPlayerClasses.forEach { className ->
        val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach

        clazz.declaredMethods.filter { it.name == "setMediaItem" || it.name == "setMediaItems" }.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val mediaItem = param.args[0] ?: return
                        val mediaItemStr = mediaItem.toString().lowercase()

                        if (mediaItemStr.contains("/ads/") ||
                            mediaItemStr.contains("sponsor") ||
                            mediaItemStr.contains("ad-") ||
                            mediaItemStr.contains("googleads")) {

                            VLogger.log("[EXO-SNIPER] Video ad blocked")
                            param.args[0] = null
                            param.result = null
                        }
                    } catch (e: Exception) {}
                }
            })
        }
    }

    val okHttpBuilder = XposedHelpers.findClassIfExists("okhttp3.Request\$Builder", classLoader)
    okHttpBuilder?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "url", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val url = param.args[0].toString().lowercase()

                if ((url.contains(".mp3") || url.contains(".mp4") || url.contains("audio-ads")) &&
                    (url.contains("ad") || url.contains("sponsor"))) {
                    VLogger.log("[RAW-NET] Audio/video ad file blocked: $url")

                    val trace = Thread.currentThread().stackTrace
                    VLogger.log("Origin: ${trace[4].className}.${trace[4].methodName}")
                }
            }
        })
    }
}

fun SpotifyHook.InstallFirehoseAndInvisibleShield() {
    VLogger.log("=== Firehose Shield Activated ===")

    val layoutInflaterClass = XposedHelpers.findClassIfExists("android.view.LayoutInflater", classLoader)

    layoutInflaterClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "inflate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val resourceId = param.args[0] as? Int ?: return
                    val inflater = param.thisObject as android.view.LayoutInflater
                    val context = inflater.context ?: return
                    val resourceName = context.resources.getResourceEntryName(resourceId).lowercase()

                    val view = param.result as? android.view.View ?: return

                    val isAdPayload = resourceName.contains("ad_overlay") ||
                            resourceName.contains("mraid_webview") ||
                            resourceName.contains("slate_ad") ||
                            resourceName.contains("npv_slate") ||
                            resourceName.contains("upsell_dialog") ||
                            resourceName.contains("promotional_banner") ||
                            resourceName.contains("snack_bar") ||
                            resourceName.contains("muted_video_ad") ||
                            resourceName.contains("embedded_npv_ad") ||
                            resourceName.contains("generic_embeddedad") ||
                            resourceName.contains("companion_ad") ||
                            resourceName.contains("recent_ads") ||
                            resourceName.contains("marquee_overlay") ||
                            resourceName.contains("sponsored_playlist") ||
                            resourceName.contains("brand_billboard") ||
                            resourceName.contains("discovery_takeover") ||
                            resourceName.contains("native_inline_engagement")

                    if (isAdPayload) {
                        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: android.view.View) {
                                v.alpha = 0.01f

                                val handler = android.os.Handler(android.os.Looper.getMainLooper())

                                val hunterRunnable = object : Runnable {
                                    override fun run() {
                                        if (v.windowToken == null) return

                                        var killed = false
                                        fun huntAndKill(group: android.view.ViewGroup) {
                                            for (i in 0 until group.childCount) {
                                                val child = group.getChildAt(i)
                                                try {
                                                    val childRes = child.context.resources.getResourceEntryName(child.id).lowercase()

                                                    if (childRes.contains("cta") || childRes.contains("learn_more") ||
                                                        childRes.contains("visit_site") || childRes.contains("action") ||
                                                        childRes.contains("close") || childRes.contains("skip") ||
                                                        childRes.contains("dismiss")) {

                                                        var detonated = false
                                                        try {
                                                            val getListenerInfo = android.view.View::class.java.getDeclaredMethod("getListenerInfo")
                                                            getListenerInfo.isAccessible = true
                                                            val listenerInfo = getListenerInfo.invoke(child)

                                                            if (listenerInfo != null) {
                                                                val mOnClickListener = listenerInfo.javaClass.getDeclaredField("mOnClickListener")
                                                                mOnClickListener.isAccessible = true
                                                                val clickListener = mOnClickListener.get(listenerInfo) as? android.view.View.OnClickListener

                                                                if (clickListener != null) {
                                                                    clickListener.onClick(child)
                                                                    VLogger.log("[FIREHOSE] Triggered button: $childRes")
                                                                    detonated = true
                                                                }
                                                            }
                                                        } catch (e: Exception) {}

                                                        if (!detonated) {
                                                            child.performClick()
                                                            child.callOnClick()
                                                        }

                                                        killed = true

                                                        handler.postDelayed({
                                                            v.visibility = android.view.View.GONE
                                                            val params = v.layoutParams
                                                            if (params != null) {
                                                                params.width = 0
                                                                params.height = 0
                                                                v.layoutParams = params
                                                            }
                                                        }, 300)

                                                        return
                                                    }
                                                } catch (e: Exception) {}
                                                if (!killed && child is android.view.ViewGroup) huntAndKill(child)
                                            }
                                        }

                                        if (v is android.view.ViewGroup) huntAndKill(v)

                                        if (!killed) {
                                            handler.postDelayed(this, 500)
                                        }
                                    }
                                }
                                handler.postDelayed(hunterRunnable, 500)
                            }

                            override fun onViewDetachedFromWindow(v: android.view.View) {}
                        })
                    }
                } catch (e: Exception) {}
            }
        })
    }
}

fun SpotifyHook.InstallScoutLogicSniper() {
    VLogger.log("=== Scout Logic Sniper Activated ===")

    val quicksilverClass = XposedHelpers.findClassIfExists("com.spotify.messaging.quicksilver.view.QuicksilverActivity", classLoader)
    quicksilverClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as android.app.Activity
                activity.finish()
                VLogger.toast("Promo popup blocked")
            }
        })
    }

    val mraidClass = XposedHelpers.findClassIfExists("com.spotify.ads.display.mraid.MraidWebView", classLoader)
    mraidClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "loadUrl", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = null
                VLogger.toast("Rich media ad blocked")
            }
        })
    }

    val ctaClass = XposedHelpers.findClassIfExists("com.spotify.ads.freetier.Cta", classLoader)
    ctaClass?.let { clazz ->
        clazz.declaredMethods.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val mName = method.name.lowercase()
                    if (mName.contains("click") || mName.contains("action") || mName.contains("open") || mName.contains("execute")) {
                        if (method.returnType == Void.TYPE) {
                            param.result = null
                            VLogger.toast("Redirect blocked")
                        }
                    }
                }
            })
        }
    }
}

fun SpotifyHook.InstallFinalBossAssassin() {
    VLogger.log("=== Final Boss Assassin Activated ===")

    val videoEngineClass = XposedHelpers.findClassIfExists("com.spotify.mobile.videonative.VideoEngine", classLoader)
    videoEngineClass?.let { clazz ->
        clazz.declaredMethods.forEach { method ->
            if (method.name.lowercase().contains("fetch") || method.name.lowercase().contains("manifest")) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        VLogger.log("[VIDEO-ENGINE] Video fetch blocked")
                        param.result = null
                    }
                })
            }
        }
    }

    val okHttpBuilder = XposedHelpers.findClassIfExists("okhttp3.Request\$Builder", classLoader)
    okHttpBuilder?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "url", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val urlStr = param.args[0].toString().lowercase()

                    val isAdNetwork = urlStr.contains("sp-ad-cdn.spotify.com") ||
                            urlStr.contains("video-ads-static.googlesyndication") ||
                            urlStr.contains("googleusercontent.com/spotify.com") ||
                            urlStr.contains("aet.spotify.com") ||
                            urlStr.contains("gabo-receiver-service") ||
                            urlStr.contains("pubads.g.doubleclick.net")

                    if (isAdNetwork) {
                        param.args[0] = "http://127.0.0.1/blackhole.mp4"
                        VLogger.log("[NETWORK-BLACKHOLE] Video proxy rerouted")
                    }
                } catch (e: Exception) {}
            }
        })
    }
}


fun SpotifyHook.InstallDeepLinkAssassin() {
    Log.e("V_SONAR", "=== Deep Link Assassin Activated ===")

    val activityClass = XposedHelpers.findClassIfExists("android.app.Activity", classLoader)
    activityClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "startActivity", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
                    val dataString = intent.dataString?.lowercase() ?: ""

                    if (dataString.contains("spotify:ad:") ||
                        dataString.contains("spotify:upsell:") ||
                        dataString.contains("spotify:promo:")) {

                        Log.e("V_SONAR", "[DEEP-LINK] Blocked: $dataString")

                        val emptyIntent = Intent()
                        val intentIndex = param.args.indexOf(intent)
                        if (intentIndex != -1) param.args[intentIndex] = emptyIntent
                    }
                } catch (e: Exception) {
                    Log.e("V_SONAR", "Deep link error: ${e.message}")
                }
            }
        })
    }
}

fun SpotifyHook.InstallMasterFeatureWeaponizer() {
    VLogger.log("=== Master Feature Weaponizer Activated ===")

    val forceTrueList = listOf(
        "app_events_killswitch",
        "bnc_ad_network_callouts_disabled",
        "bnc_limit_facebook_tracking",
        "deferred_analytics_collection",
        "FBSDKFeatureRestrictiveDataFiltering",
        "FBSDKFeaturePrivacyProtection",
        "FBSDKFeaturePIIFiltering",
        "FBSDKFeatureEventDeactivation",
        "FBSDKFeatureFilterSensitiveParams",
        "FBSDKFeatureBannedParamFiltering",
        "picture_in_picture",
        "shake_to_report",
        "android-media-session.media3_enabled",
        "create_button_enabled",
        "key_lyrics_on_npv_visible"
    )

    val forceFalseList = listOf(
        "auto_event_setup_enabled",
        "app_events_if_auto_log_subs",
        "FBSDKFeatureIAPLogging",
        "FBSDKFeatureIAPLoggingLib2",
        "FBSDKFeatureIAPLoggingLib5To7",
        "FBSDKFeatureIAPLoggingSK2",
        "FBSDKFeatureCodelessEvents",
        "FBSDKFeatureAppEventsCloudbridge",
        "FBSDKFeatureAEM",
        "fb_mobile_purchase"
    )

    val sharedPrefsClass = XposedHelpers.findClassIfExists("android.app.SharedPreferencesImpl", classLoader)
    sharedPrefsClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "getBoolean", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as? String ?: return

                if (forceTrueList.contains(key) && param.result != true) {
                    param.result = true
                    VLogger.log("[CACHE] Enabled: $key")
                } else if (forceFalseList.contains(key) && param.result != false) {
                    param.result = false
                    VLogger.log("[CACHE] Disabled: $key")
                }
            }
        })
    }

    val jsonObjectClass = XposedHelpers.findClassIfExists("org.json.JSONObject", classLoader)
    jsonObjectClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "optBoolean", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as? String ?: return

                if (forceTrueList.contains(key) && param.result != true) {
                    param.result = true
                } else if (forceFalseList.contains(key) && param.result != false) {
                    param.result = false
                }
            }
        })

        XposedBridge.hookAllMethods(clazz, "getBoolean", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as? String ?: return

                if (forceTrueList.contains(key)) {
                    param.result = true
                } else if (forceFalseList.contains(key)) {
                    param.result = false
                }
            }
        })
    }
}

fun SpotifyHook.InstallVideoAdForensicsAndKiller() {
    VLogger.log("=== Video Ad Forensics Activated ===")

    val okHttpBuilderClass = XposedHelpers.findClassIfExists("okhttp3.Request\$Builder", classLoader)
    okHttpBuilderClass?.let { clazz ->
        XposedBridge.hookAllMethods(clazz, "url", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val urlStr = param.args[0].toString().lowercase()

                    val isVideoProxy = urlStr.contains("googleusercontent.com/spotify.com")
                    val isTracker = urlStr.contains("cdn.branch.io") || urlStr.contains("uriskiplist")

                    if (isVideoProxy || isTracker) {
                        VLogger.log("[VIDEO-PROXY] Blocked: $urlStr")

                        val trace = Thread.currentThread().stackTrace
                        for (i in 3..8) {
                            if (i < trace.size) {
                                VLogger.log("   Origin: ${trace[i].className}.${trace[i].methodName}")
                            }
                        }

                        param.args[0] = "http://127.0.0.1/v_blackhole_video_ad.mp4"
                    }
                } catch (e: Exception) {}
            }
        })
    }

    val exoClasses = listOf(
        "com.google.android.exoplayer2.ExoPlayerImpl",
        "androidx.media3.exoplayer.ExoPlayerImpl",
        "com.google.android.exoplayer2.SimpleExoPlayer"
    )

    exoClasses.forEach { className ->
        val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: return@forEach
        clazz.declaredMethods.filter { it.name == "setMediaItem" || it.name == "setMediaItems" || it.name == "addMediaItem" }.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val mediaItem = param.args[0] ?: return
                        val mediaItemStr = mediaItem.toString().lowercase()

                        if (mediaItemStr.contains("googleusercontent.com") || mediaItemStr.contains("ad")) {
                            VLogger.log("[EXO-CHOKEHOLD] Video proxy intercepted")
                            param.args[0] = null
                            param.result = null
                        }
                    } catch (e: Exception) {}
                }
            })
        }
    }

    val inflaterClass = java.util.zip.Inflater::class.java
    XposedBridge.hookAllMethods(inflaterClass, "inflate", object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                val outputBuffer = param.args[0] as? ByteArray ?: return
                val bytesRead = param.result as? Int ?: return

                if (bytesRead > 20) {
                    val snippet = String(outputBuffer.take(bytesRead.coerceAtMost(1024)).toByteArray(), Charsets.UTF_8).lowercase()

                    if (snippet.contains("maxads") || snippet.contains("leavebehindads") || snippet.contains("requestId")) {
                        VLogger.log("[MANIFEST-SNIFFER] Ad manifest intercepted")
                    }
                }
            } catch (e: Exception) {}
        }
    })
}

// Call this exactly once when the app or your patch initializes
fun startLockTimer() {
    Thread {
        try {
            // Wait 10 seconds (10,000 milliseconds) for the user to log in and the UI to cache
            Thread.sleep(10000)

            // Grab the context internally
            val context = Utils.getContext() ?: return@Thread

            val lockFile = File(context.getExternalFilesDir(null), "v_startup_completed.lock")

            if (!lockFile.exists()) {
                lockFile.createNewFile()
            }
        } catch (e: Exception) {
            // Silent fail.
        }
    }.start()
}

// ====================================================================
// V'S KOTLIN LOGGER (LOGCAT + MAP.TXT)
// ====================================================================
object VKotlinLogger {
    fun log(message: String) {
        // 1. Blast to Logcat
        android.util.Log.e("V-KOTLIN-TRACE", message)

        // 2. Blast to map.txt
        try {
            val ctx = app.revanced.extension.shared.Utils.getContext()
            val dir = ctx?.getExternalFilesDir(null)
                ?: java.io.File("/storage/emulated/0/Android/data/com.spotify.music/files")

            if (dir.exists() || dir.mkdirs()) {
                val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
                java.io.File(dir, "map.txt").appendText("[$time] $message\n")
            }
        } catch (e: Exception) {
            // Ignore disk errors to keep the app from crashing
        }
    }
}

// ====================================================================
// V'S TOTAL RECON & SCHRÖDINGER MAP (UPDATED TARGET LIST)
// ====================================================================
class VTotalReconMap(
    private val originalMap: Map<String, Any?>,
    private val spoofedMap: Map<String, Any?>
) : java.util.LinkedHashMap<String, Any?>(spoofedMap) {

    private fun logAndCheckSnitch(action: String, key: String = "N/A"): Boolean {
        val traceElements = Thread.currentThread().stackTrace
        var isSnitch = false
        val traceDump = java.lang.StringBuilder()

        var count = 0
        for (element in traceElements) {
            val className = element.className.lowercase()
            if (className.contains("vtotalreconmap") || className.contains("xposed") ||
                className.contains("chsbuffer") || className.startsWith("java.") ||
                className.startsWith("dalvik") || className.startsWith("android.")) {
                continue
            }

            traceDump.append("    -> ${element.className}.${element.methodName}(Line: ${element.lineNumber})\n")

            // 🚨 THE KILL-LIST: Added v9e0 based on our recon data!
            if (className.contains("protobuf") || className.contains("eventsender") ||
                className.contains("telemetry") || className.contains("cosmos") ||
                className.contains("remoteconfig") || className.contains("v9e0")) {
                isSnitch = true
            }

            count++
            if (count >= 10) break
        }

        val snitchStatus = if (isSnitch) "🚨 SNITCH BLOCKED (Returned Free-Tier Data)" else "✅ UI GRANTED (Returned Premium Data)"

        val logMsg = "\n🔍 [MAP ACCESS: $action] Key: $key | $snitchStatus\n$traceDump"
        VKotlinLogger.log(logMsg) // Writes to Logcat AND map.txt

        return isSnitch
    }

    override operator fun get(key: String): Any? {
        val isSnitch = logAndCheckSnitch("GET", key)
        return if (isSnitch) originalMap[key] else super.get(key)
    }

    override fun getOrDefault(key: String, defaultValue: Any?): Any? {
        val isSnitch = logAndCheckSnitch("GET_OR_DEFAULT", key)
        return if (isSnitch) originalMap.getOrDefault(key, defaultValue) else super.getOrDefault(key, defaultValue)
    }

    override fun containsKey(key: String): Boolean {
        val isSnitch = logAndCheckSnitch("CONTAINS_KEY", key)
        return if (isSnitch) originalMap.containsKey(key) else super.containsKey(key)
    }

    override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>>
        get() {
            val isSnitch = logAndCheckSnitch("ITERATE_ENTRIES")
            return if (isSnitch) java.util.LinkedHashMap(originalMap).entries else super.entries
        }

    override val keys: MutableSet<String>
        get() {
            val isSnitch = logAndCheckSnitch("ITERATE_KEYS")
            return if (isSnitch) java.util.LinkedHashMap(originalMap).keys else super.keys
        }

    override val values: MutableCollection<Any?>
        get() {
            val isSnitch = logAndCheckSnitch("ITERATE_VALUES")
            return if (isSnitch) java.util.LinkedHashMap(originalMap).values else super.values
        }
}

// ---------------------------------------------------------
// THE HOOK
// ---------------------------------------------------------
fun SpotifyHook.InstallWorkingProductStateHook() {
    VKotlinLogger.log("=== Product State Hook Activated (KOTLIN RECON + MAP.TXT) ===")

    ::productStateProtoFingerprint.hookMethod {
        after { param ->
            val originalMap = param.result as? Map<String, *> ?: return@after

            val traceElements = Thread.currentThread().stackTrace
            val traceDump = java.lang.StringBuilder()
            var count = 0
            for (element in traceElements) {
                val className = element.className
                if (!className.contains("Xposed") && !className.contains("chsbuffer") &&
                    !className.startsWith("dalvik") && !className.startsWith("java.")) {
                    traceDump.append("    -> ${element.className}.${element.methodName}(Line: ${element.lineNumber})\n")
                    count++
                    if (count >= 10) break
                }
            }
            VKotlinLogger.log("\n⚙️ NEW MAP GENERATED ⚙️\n$traceDump")

            @Suppress("UNCHECKED_CAST")
            val spoofedMap = UnlockPremiumPatch.createOverriddenAttributesMap(originalMap) as Map<String, Any?>

            param.result = VTotalReconMap(originalMap, spoofedMap)
        }
    }
}

fun SpotifyHook.InstallUiDumperButton() {
    android.util.Log.e("V-UI-DUMPER", "=== UI Dumper Button Armed ===")

    // Helper function to recursively scrape every view and its properties
    fun dumpViewHierarchy(view: View, depth: Int, sb: java.lang.StringBuilder) {
        val indent = "  ".repeat(depth)
        val className = view.javaClass.name

        // Try to resolve the human-readable XML ID if it has one
        val idName = try {
            if (view.id != View.NO_ID && view.resources != null) {
                view.resources.getResourceEntryName(view.id)
            } else "NO_ID"
        } catch (e: Exception) { "UNKNOWN_ID" }

        val visibility = when (view.visibility) {
            View.VISIBLE -> "VIS"
            View.INVISIBLE -> "INV"
            View.GONE -> "GONE"
            else -> "???"
        }

        var extraInfo = ""
        if (view is TextView) {
            val txt = view.text?.toString()?.replace("\n", "\\n") ?: ""
            if (txt.isNotEmpty()) extraInfo += " | TEXT: \"$txt\""
        }
        if (!view.contentDescription.isNullOrEmpty()) {
            extraInfo += " | DESC: \"${view.contentDescription}\""
        }
        if (view.hasOnClickListeners()) {
            extraInfo += " [CLICKABLE]"
        }

        sb.append("$indent-> $className (id: $idName) [$visibility]$extraInfo\n")

        // If it's a layout/group, dive deeper
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                dumpViewHierarchy(view.getChildAt(i), depth + 1, sb)
            }
        }
    }

    // Attach to the app's lifecycle so the button follows you to every screen
    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            val root = activity.window.decorView as? ViewGroup ?: return

            // Don't add it twice if it's already there
            if (root.findViewWithTag<View>("V_UI_DUMPER_BTN") != null) return

            val dumperBtn = TextView(activity).apply {
                tag = "V_UI_DUMPER_BTN"
                text = "📸 DUMP UI"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(30, 20, 30, 20)

                // Hacker red, slightly transparent so it doesn't block the app completely
                background = GradientDrawable().apply {
                    cornerRadius = 20f
                    setColor(Color.parseColor("#CCFF0044")) // 80% opacity red
                    setStroke(2, Color.WHITE)
                }

                // Force it to the absolute top of the screen render stack
                translationZ = 99999f
                elevation = 99999f

                layoutParams = FrameLayout.LayoutParams(-2, -2).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = 150 // Push it down slightly below the status bar
                }

                setOnClickListener {
                    Toast.makeText(activity, "V: Ripping UI layout...", Toast.LENGTH_SHORT).show()

                    // Run off the main thread so we don't freeze the app while scraping
                    Thread {
                        try {
                            val sb = java.lang.StringBuilder()
                            sb.append("=== V'S TACTICAL UI DUMP ===\n")
                            sb.append("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n\n")

                            // Use WindowManagerGlobal to catch floating menus and dialogs, not just the main Activity
                            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
                            val wmg = wmgClass.getMethod("getInstance").invoke(null)
                            val mViewsField = wmgClass.getDeclaredField("mViews")
                            mViewsField.isAccessible = true

                            @Suppress("UNCHECKED_CAST")
                            val rootViews = mViewsField.get(wmg) as List<View>

                            for ((index, windowRoot) in rootViews.withIndex()) {
                                sb.append("--- WINDOW $index: ${windowRoot.javaClass.simpleName} ---\n")
                                dumpViewHierarchy(windowRoot, 1, sb)
                                sb.append("\n")
                            }

                            // Write directly to your target file
                            val dir = File("/storage/emulated/0/Android/data/com.spotify.music/files")
                            if (!dir.exists()) dir.mkdirs()
                            val dumpFile = File(dir, "view_dump.txt")

                            dumpFile.writeText(sb.toString())

                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(activity, "V: Blueprint saved to view_dump.txt!", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(activity, "V: Dump failed! ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }
            }

            root.addView(dumperBtn)
        }

        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    })
}

@Suppress("UNCHECKED_CAST")
fun SpotifyHook.UnlockPremium(prefs: de.robv.android.xposed.XSharedPreferences) {

    if (prefs.getBoolean("enable_visual_ads_block", true)) {
        InstallVideoAdForensicsAndKiller()
        InstallWorkingProductStateHook()
        // InstallLibraryHijacker()
        InstallAnonymousPageMangler()
        // performStringManglingSurgeon()
        // InstallUiDumperButton() ui dumper
        // startLockTimer()
        InstallFinalBossAssassin()
        InstallScoutLogicSniper()
        InstallGlobalUIAssassin()
        InstallSlateModalAssassin()
        InstallVisualAdSniper()
        InstallWorkingPendragonFix()
        InstallFirehoseAndInvisibleShield()
    }

    if (prefs.getBoolean("enable_audio_ads_block", true)) {
        InstallAdAutoSkip()
        InstallNuclearAdScrubber()
        InstallAdChoke()
    }

    if (prefs.getBoolean("enable_ui_fixes", true)) {
        InstallDeepLinkAssassin()
        InstallWorkingAdsRemoval()
        InstallPlayabilityForcer()
    }

    XposedHelpers.findAndHookMethod(
        "com.spotify.player.model.command.options.AutoValue_PlayerOptionOverrides\$Builder",
        classLoader,
        "build",
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.thisObject.callMethod("shufflingContext", false)
            }
        }
    )

    val contextMenuViewModelClazz = ::contextMenuViewModelClass.clazz
    XposedBridge.hookAllConstructors(
        contextMenuViewModelClazz, object : XC_MethodHook() {
            val isPremiumUpsell = ::isPremiumUpsellField.field

            override fun beforeHookedMethod(param: MethodHookParam) {
                val parameterTypes = (param.method as java.lang.reflect.Constructor<*>).parameterTypes
                for (i in 0 until param.args.size) {
                    if (parameterTypes[i].name != "java.util.List") continue
                    val original = param.args[i] as? List<*> ?: continue
                    val filtered = original.filter {
                        it!!.callMethod("getViewModel").let { isPremiumUpsell.get(it) } != true
                    }
                    param.args[i] = filtered
                }
            }
        }
    )
}
