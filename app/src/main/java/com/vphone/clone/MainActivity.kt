package com.vphone.clone

import android.content.res.AssetManager
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var consoleLayout: LinearLayout
    private lateinit var cmdInput: EditText
    private lateinit var btnToggle: Button
    private var linuxWriter: BufferedWriter? = null

    // Runtime download URLs
    private val PROOT_URL =
        "https://github.com/termux/proot/releases/download/v5.1.107/proot-android-aarch64"
    private val ROOTFS_URL =
        "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // UI bindings
        logView = findViewById(R.id.logView)
        consoleLayout = findViewById(R.id.terminalConsole)
        cmdInput = findViewById(R.id.cmdInput)
        btnToggle = findViewById(R.id.btnToggle)

        // Toggle console overlay
        btnToggle.setOnClickListener {
            consoleLayout.visibility =
                if (consoleLayout.visibility == LinearLayout.VISIBLE) LinearLayout.GONE
                else LinearLayout.VISIBLE
        }

        // Send commands on IME action
        cmdInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val cmd = (v as EditText).text.toString()
                executeLinux(cmd)
                v.setText("")
                true
            } else false
        }

        // Initialize Proot + Alpine container
        initOS()
    }

    /** Initialize Proot + Alpine rootfs */
    private fun initOS() {
        val binDir = File(filesDir, "bin").apply { mkdirs() }
        val rootfsDir = File(filesDir, "rootfs")
        val prootBin = File(binDir, "proot")
        val rootfsTar = File(filesDir, "rootfs.tar.gz")

        Thread {
            try {
                // 1️⃣ Download Proot if missing
                if (!prootBin.exists()) {
                    updateStatus("Downloading Proot for Android...")
                    downloadFile(PROOT_URL, prootBin)
                    prootBin.setExecutable(true)
                    updateStatus("Proot downloaded: ${prootBin.absolutePath}")
                }

                // 2️⃣ Download rootfs if missing
                if (!rootfsTar.exists()) {
                    updateStatus("Downloading Alpine rootfs...")
                    downloadFile(ROOTFS_URL, rootfsTar)
                    updateStatus("Rootfs downloaded: ${rootfsTar.absolutePath}")
                }

                // 3️⃣ Extract rootfs if missing
                if (!rootfsDir.exists() || rootfsDir.list()?.isEmpty() == true) {
                    rootfsDir.mkdirs()
                    updateStatus("Extracting Alpine rootfs...")
                    val tarProcess = Runtime.getRuntime().exec(
                        arrayOf("tar", "-xzf", rootfsTar.absolutePath, "-C", rootfsDir.absolutePath)
                    )
                    if (tarProcess.waitFor() != 0) throw Exception("Failed to extract rootfs")
                    updateStatus("Rootfs extracted to: ${rootfsDir.absolutePath}")
                }

                // 4️⃣ Launch Alpine via Proot
                bootLinux(prootBin, rootfsDir)

            } catch (e: Exception) {
                updateStatus("ERROR: ${e.message}")
            }
        }.start()
    }

    /** Download a file from URL to target location */
    private fun downloadFile(urlString: String, targetFile: File) {
        val url = URL(urlString)
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            requestMethod = "GET"
            connect()
            inputStream.use { input ->
                FileOutputStream(targetFile).use { output -> input.copyTo(output) }
            }
            disconnect()
        }
    }

    /** Launch Alpine via Proot */
    private fun bootLinux(prootBin: File, rootfsDir: File) {
        val cmd = arrayOf(
            prootBin.absolutePath,
            "-0",                 // root user inside container
            "-r", rootfsDir.absolutePath,
            "/bin/sh", "-c",
            "echo 'Alpine loaded!' && exec /bin/sh -i"
        )

        try {
            val process = Runtime.getRuntime().exec(cmd)
            linuxWriter = process.outputStream.bufferedWriter()

            // Capture stdout
            Thread { process.inputStream.bufferedReader().forEachLine { updateStatus(it) } }.start()
            // Capture stderr
            Thread { process.errorStream.bufferedReader().forEachLine { updateStatus("ERR: $it") } }.start()

            updateStatus("Alpine container launched via Proot")

        } catch (e: Exception) {
            updateStatus("Failed to launch Alpine: ${e.message}")
        }
    }

    /** Execute Linux command inside container */
    private fun executeLinux(cmd: String) {
        Thread {
            try {
                linuxWriter?.write("$cmd\n")
                linuxWriter?.flush()
            } catch (e: Exception) {
                updateStatus("Shell error: ${e.message}")
            }
        }.start()
    }

    /** Append text to TextView and scroll */
    private fun updateStatus(text: String) {
        runOnUiThread {
            logView.append("\n$text")
            (logView.parent as? ScrollView)?.post {
                (logView.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }
}
