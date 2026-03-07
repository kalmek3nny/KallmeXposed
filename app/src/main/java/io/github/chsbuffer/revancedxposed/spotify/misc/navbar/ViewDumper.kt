package io.github.chsbuffer.revancedxposed.spotify.misc.navbar

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ViewDumper {

    private val handler = Handler(Looper.getMainLooper())

    fun init(app: Application) {
        // Run a silent background loop that dumps the UI every 5 seconds!
        val dumpRunnable = object : Runnable {
            override fun run() {
                performDump(app)
                handler.postDelayed(this, 5000)
            }
        }

        // Start the loop 5 seconds after the app launches
        handler.postDelayed(dumpRunnable, 5000)
    }

    private fun performDump(app: Application) {
        Toast.makeText(app, "📸 Auto-Dumping UI...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val sb = StringBuilder()
                sb.append("=== SPOTIFY UI DUMP ===\n")
                sb.append("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n\n")

                // We use getRootViews to grab EVERYTHING on the screen
                // (This catches the Activity AND the floating Context Menus!)
                val windowManagerGlobalClass = Class.forName("android.view.WindowManagerGlobal")
                val windowManagerGlobal = windowManagerGlobalClass.getMethod("getInstance").invoke(null)
                val mViewsField = windowManagerGlobalClass.getDeclaredField("mViews")
                mViewsField.isAccessible = true

                @Suppress("UNCHECKED_CAST")
                val rootViews = mViewsField.get(windowManagerGlobal) as List<View>

                for ((index, root) in rootViews.withIndex()) {
                    sb.append("--- WINDOW $index: ${root.javaClass.simpleName} ---\n")
                    if (root is ViewGroup) {
                        traverseView(root, 1, sb)
                    }
                    sb.append("\n")
                }

                val dir = app.getExternalFilesDir(null)
                val file = File(dir, "spotify_ui_dump.txt")
                file.writeText(sb.toString())

            } catch (e: Exception) {
                XposedBridge.log("Dump Failed: ${e.message}")
            }
        }.start()
    }

    private fun traverseView(view: View, depth: Int, sb: StringBuilder) {
        val indent = "  ".repeat(depth)

        val className = view.javaClass.name

        val idName = try {
            if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id) else "NO_ID"
        } catch (e: Exception) { "UNKNOWN_ID" }

        val visibility = when (view.visibility) {
            View.VISIBLE -> "VISIBLE"
            View.INVISIBLE -> "INVISIBLE"
            View.GONE -> "GONE"
            else -> "UNKNOWN"
        }

        var textContent = ""
        if (view is TextView) {
            val txt = view.text?.toString()?.replace("\n", "\\n") ?: ""
            if (txt.isNotEmpty()) textContent = " | TEXT: \"$txt\""
        }

        var contentDesc = ""
        if (view.contentDescription != null) {
            contentDesc = " | DESC: \"${view.contentDescription}\""
        }

        val clickable = if (view.hasOnClickListeners()) " [CLICKABLE]" else ""

        sb.append("$indent-> $className (id: $idName) [$visibility]$clickable$textContent$contentDesc\n")

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                traverseView(view.getChildAt(i), depth + 1, sb)
            }
        }
    }
}