package io.github.chsbuffer.revancedxposed.spotify.misc.spoofs

import android.content.ContentResolver
import android.net.wifi.WifiInfo
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import java.io.File
import java.io.FileWriter
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import kotlin.random.Random

fun SpotifyHook.InstallDeviceSpoofer() {
    val errorLog = mutableListOf<String>()

    // ==========================================
    // 1. THE PERSISTENT GHOST IDENTITY (COLD STORAGE)
    // ==========================================
    val identityFile = File(app.getExternalFilesDir(null), "v_persistent_ghost_identity.properties")
    val props = Properties()

    if (identityFile.exists()) {
        try {
            identityFile.inputStream().use { props.load(it) }
            XposedBridge.log("👻 [V-GHOST] Loaded persistent identity from cold storage.")
        } catch (e: Exception) {
            XposedBridge.log("⚠️ [V-GHOST] Failed to load identity, regenerating.")
        }
    }

    // Helper to generate a value only if it doesn't already exist
    fun getOrGenerate(key: String, generator: () -> String): String {
        if (!props.containsKey(key)) {
            props.setProperty(key, generator())
        }
        return props.getProperty(key)
    }

    // 🎯 Generate the persistent identifiers
    val fakeAndroidId = getOrGenerate("ANDROID_ID") { List(16) { "0123456789abcdef".random() }.joinToString("") }
    val fakeMacAddress = getOrGenerate("MAC_ADDRESS") { "02:00:00:%02x:%02x:%02x".format(Random.nextInt(256), Random.nextInt(256), Random.nextInt(256)) }
    val fakeSerial = getOrGenerate("SERIAL") { List(10) { "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".random() }.joinToString("") }
    val fakeImei = getOrGenerate("IMEI") { List(15) { "0123456789".random() }.joinToString("") }
    val fakeGsfId = getOrGenerate("GSF_ID") { Random.nextLong(1000000000000000000L, Long.MAX_VALUE).toString(16) }

    // 🎯 Expand the spoofing profile (Verified Pixel 7 Cover Story)
    val chosenModel = getOrGenerate("MODEL") { "Pixel 7" }
    val chosenManufacturer = getOrGenerate("MANUFACTURER") { "Google" }
    val chosenBrand = getOrGenerate("BRAND") { "google" }
    val chosenDevice = getOrGenerate("DEVICE") { "panther" }
    val chosenProduct = getOrGenerate("PRODUCT") { "panther" }
    val chosenHardware = getOrGenerate("HARDWARE") { "panther" }
    val chosenBoard = getOrGenerate("BOARD") { "panther" }
    val chosenFingerprint = getOrGenerate("FINGERPRINT") { "google/panther/panther:13/TQ3A.230805.001/10316531:user/release-keys" }
    val chosenCarrier = getOrGenerate("CARRIER") { listOf("T-Mobile", "Verizon", "AT&T").random() }

    // Save state back to disk
    try {
        identityFile.outputStream().use { props.store(it, "V's Persistent Ghost Identity - DO NOT DELETE") }
    } catch (e: Exception) {
        errorLog.add("Failed to save identity to disk: ${e.message}")
    }

    // ==========================================
    // 2. APPLY THE HOOKS (THE INJECTION)
    // ==========================================

    // 1. Spoof ANDROID_ID & GSF ID
    try {
        XposedHelpers.findAndHookMethod(
            Settings.Secure::class.java, "getString", ContentResolver::class.java, String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    when (param.args[1]) {
                        Settings.Secure.ANDROID_ID -> param.result = fakeAndroidId
                        "android_id" -> param.result = fakeAndroidId // Catch legacy calls
                    }
                }
            }
        )
        // GSF ID hook (often queried by ad networks/telemetry via Google Services)
        val gsfUri = android.net.Uri.parse("content://com.google.android.gsf.gservices")
        XposedBridge.hookAllMethods(ContentResolver::class.java, "query", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val uri = param.args[0] as? android.net.Uri ?: return
                if (uri == gsfUri) {
                    // We don't implement the full cursor fake here to save CPU,
                    // but we log it so we know if Spotify is hunting for it.
                    XposedBridge.log("👻 [V-GHOST] Blocked GSF ID query.")
                    param.result = null
                }
            }
        })
    } catch (e: Throwable) { errorLog.add("Android ID Hook Failed: ${e.stackTraceToString()}") }

    // 2. Spoof Hardware Build.prop & Integrity (The Deep Cover)
    try {
        XposedHelpers.setStaticObjectField(Build::class.java, "SERIAL", fakeSerial)
        XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", chosenModel)
        XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", chosenManufacturer)
        XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", chosenBrand)
        XposedHelpers.setStaticObjectField(Build::class.java, "DEVICE", chosenDevice)
        XposedHelpers.setStaticObjectField(Build::class.java, "PRODUCT", chosenProduct)
        XposedHelpers.setStaticObjectField(Build::class.java, "HARDWARE", chosenHardware)
        XposedHelpers.setStaticObjectField(Build::class.java, "BOARD", chosenBoard)
        XposedHelpers.setStaticObjectField(Build::class.java, "FINGERPRINT", chosenFingerprint)
        XposedHelpers.setStaticObjectField(Build::class.java, "TAGS", "release-keys")
        XposedHelpers.setStaticObjectField(Build::class.java, "TYPE", "user")

        XposedHelpers.findAndHookMethod(Build::class.java, "getSerial", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = fakeSerial
            }
        })
    } catch (e: Throwable) { errorLog.add("Build.prop Hook Failed: ${e.stackTraceToString()}") }

    // 3. Spoof Wi-Fi MAC Address
    try {
        XposedHelpers.findAndHookMethod(WifiInfo::class.java, "getMacAddress", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = fakeMacAddress
            }
        })

        XposedHelpers.findAndHookMethod(NetworkInterface::class.java, "getHardwareAddress", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = fakeMacAddress.split(":").map { it.toInt(16).toByte() }.toByteArray()
            }
        })
    } catch (e: Throwable) { errorLog.add("MAC Address Hook Failed: ${e.stackTraceToString()}") }

    // 4. Spoof Telephony & Carrier Identity
    try {
        val telephonyClass = TelephonyManager::class.java
        val phoneSpoofer = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { param.result = fakeImei }
        }
        val carrierSpoofer = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { param.result = chosenCarrier }
        }

        XposedHelpers.findAndHookMethod(telephonyClass, "getDeviceId", phoneSpoofer)
        XposedHelpers.findAndHookMethod(telephonyClass, "getImei", phoneSpoofer)
        XposedHelpers.findAndHookMethod(telephonyClass, "getMeid", phoneSpoofer)
        XposedHelpers.findAndHookMethod(telephonyClass, "getSubscriberId", phoneSpoofer)
        XposedHelpers.findAndHookMethod(telephonyClass, "getSimOperatorName", carrierSpoofer)
        XposedHelpers.findAndHookMethod(telephonyClass, "getNetworkOperatorName", carrierSpoofer)

        // Hide roaming and network type info that could flag us
        XposedHelpers.findAndHookMethod(telephonyClass, "isNetworkRoaming", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { param.result = false }
        })
    } catch (e: Throwable) { errorLog.add("Telephony Hook Failed: ${e.stackTraceToString()}") }

    // 5. Aggressive File System Stealth (Hides root, emulators, and Xposed)
    try {
        XposedBridge.hookAllConstructors(File::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.isEmpty()) return
                val path = param.args[0]?.toString()?.lowercase() ?: return

                val killList = listOf(
                    "lspd", "liblsp.so", "xposed", "edxposed", "magisk",
                    "su", "superuser", "daemonsu", "qemu", "bluestacks",
                    "twrp", "v_java_honeypot.txt" // Hiding our own tracks
                )

                if (killList.any { path.contains(it) }) {
                    param.args[0] = "/dev/null/v_ghost_path"
                }
            }
        })
    } catch (e: Throwable) { errorLog.add("Filesystem Stealth Hook Failed: ${e.stackTraceToString()}") }

    // 6. Memory Stack Trace Sanitizer (The Ultimate Anti-Cheat Blindfold)
    try {
        XposedHelpers.findAndHookMethod(Throwable::class.java, "getStackTrace", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val trace = param.result as? Array<StackTraceElement> ?: return

                // Filter out any stack trace lines that mention Xposed or LSPosed
                val cleanTrace = trace.filterNot {
                    it.className.contains("xposed", ignoreCase = true) ||
                            it.className.contains("lsposed", ignoreCase = true) ||
                            it.className.contains("chsbuffer", ignoreCase = true) // Hide your package name!
                }.toTypedArray()

                param.result = cleanTrace
            }
        })
    } catch (e: Throwable) { errorLog.add("Stack Trace Sanitizer Failed: ${e.stackTraceToString()}") }

    // ==========================================
    // VERIFICATION & LOGGING
    // ==========================================
    if (errorLog.isEmpty()) {
        Toast.makeText(app, "All spoofs succeeded, have fun!", Toast.LENGTH_LONG).show()
        XposedBridge.log("V_SONAR: Full device spoofing hooked successfully!")
    } else {
        Toast.makeText(app, "Spoofs failed", Toast.LENGTH_LONG).show()

        try {
            val logFile = File(app.getExternalFilesDir(null), "V_Spoofer_Errors.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

            val writer = FileWriter(logFile, true)
            writer.appendLine("=== V-GHOST ERROR LOG [$timestamp] ===")
            errorLog.forEach { error ->
                writer.appendLine(error)
                XposedBridge.log("V_SONAR ERROR: $error")
            }
            writer.appendLine("=====================================\n")
            writer.close()
        } catch (e: Exception) {
            XposedBridge.log("V_SONAR: Failed to write error log to disk! ${e.message}")
        }
    }
}