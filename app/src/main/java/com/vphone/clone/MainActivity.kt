package com.vphone.clone

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

    // URLs for runtime download
    private val QEMU_URL =
        "https://github.com/multiarch/qemu-user-static/releases/download/v7.2.0-1/qemu-aarch64-static"
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

        // Start optional logcat thread
        startLogcatThread()

        // Initialize OS: download QEMU + rootfs and boot container
        initOS()
    }

    /** Initialize QEMU + Alpine container */
    private fun initOS() {
        val binDir = File(filesDir, "bin").apply { mkdirs() }
        val rootfsDir = File(filesDir, "rootfs")
        val qemuBin = File(binDir, "qemu-aarch64")
        val rootfsTar = File(filesDir, "rootfs.tar.gz")

        Thread {
            try {
                // Download QEMU if missing
                if (!qemuBin.exists()) {
                    updateStatus("Downloading QEMU user-mode...")
                    downloadFile(QEMU_URL, qemuBin)
                    qemuBin.setExecutable(true)
                    updateStatus("QEMU downloaded: ${qemuBin.absolutePath}")
                }

                // Download rootfs if missing
                if (!rootfsTar.exists()) {
                    updateStatus("Downloading Alpine rootfs (may take a minute)...")
                    downloadFile(ROOTFS_URL, rootfsTar)
                    updateStatus("Rootfs downloaded: ${rootfsTar.absolutePath}")
                }

                // Extract rootfs if empty
                if (!rootfsDir.exists() || rootfsDir.list()?.isEmpty() == true) {
                    rootfsDir.mkdirs()
                    updateStatus("Extracting Alpine rootfs...")
                    val tarProcess = Runtime.getRuntime().exec(
                        arrayOf("tar", "-xzf", rootfsTar.absolutePath, "-C", rootfsDir.absolutePath)
                    )
                    if (tarProcess.waitFor() != 0) throw Exception("Failed to extract rootfs")
                    updateStatus("Rootfs extracted to: ${rootfsDir.absolutePath}")
                }

                // Boot the container
                bootLinux(qemuBin, rootfsDir)

            } catch (e: Exception) {
                updateStatus("ERROR: ${e.message}")
            }
        }.start()
    }

    /** Download file from URL */
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

    /** Boot Alpine using QEMU user-mode */
    private fun bootLinux(qemuBin: File, rootfsDir: File) {
        val cmd = arrayOf(
            qemuBin.absolutePath,
            "-L", rootfsDir.absolutePath,
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

            updateStatus("Alpine container launched via QEMU")

        } catch (e: Exception) {
            updateStatus("Failed to launch Alpine: ${e.message}")
        }
    }

    /** Execute Linux command in the container */
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

    /** Optional: capture Android logcat in-app */
    private fun startLogcatThread() {
        Thread {
            try {
                val logcat = Runtime.getRuntime().exec("logcat VPhone:D *:S")
                logcat.inputStream.bufferedReader().forEachLine { updateStatus("[LOGCAT] $it") }
            } catch (e: Exception) {
                updateStatus("Logcat thread error: ${e.message}")
            }
        }.start()
    }
}
