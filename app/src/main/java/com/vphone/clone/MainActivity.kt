package com.vphone.clone

import android.os.Build
import android.os.Bundle
import android.system.Os
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var consoleLayout: LinearLayout
    private lateinit var cmdInput: EditText
    private lateinit var btnToggle: Button
    private var linuxWriter: BufferedWriter? = null
    private var linuxProcess: Process? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        consoleLayout = findViewById(R.id.terminalConsole)
        cmdInput = findViewById(R.id.cmdInput)
        btnToggle = findViewById(R.id.btnToggle)

        btnToggle.setOnClickListener {
            consoleLayout.visibility =
                if (consoleLayout.visibility == LinearLayout.VISIBLE) LinearLayout.GONE
                else LinearLayout.VISIBLE
        }

        cmdInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val cmd = (v as EditText).text.toString()
                executeLinux(cmd)
                v.setText("")
                true
            } else false
        }

        initOS()
    }

    private fun initOS() {
        // Use the system's native library path to bypass API 29+ execution restrictions
        val prootBin = File(applicationInfo.nativeLibraryDir, "libproot.so")
        val rootfsDir = File(filesDir, "rootfs")
        val rootfsTar = File(filesDir, "rootfs.tar.gz")

        Thread {
            try {
                if (!prootBin.exists()) {
                    updateStatus("ERROR: libproot.so missing from jniLibs!")
                    return@Thread
                }

                // Download Alpine rootfs if missing
                val ROOTFS_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz"
                if (!rootfsTar.exists()) {
                    updateStatus("Downloading Alpine rootfs...")
                    downloadFile(ROOTFS_URL, rootfsTar)
                }

                // Extract with Symlink support
                if (!rootfsDir.exists() || rootfsDir.list().isNullOrEmpty()) {
                    rootfsDir.mkdirs()
                    extractTarGz(rootfsTar, rootfsDir)
                    updateStatus("Rootfs ready.")
                }

                bootLinux(prootBin, rootfsDir)

            } catch (e: Exception) {
                updateStatus("ERROR: ${e.message}")
                e.printStackTrace()
            }
        }.start()
    }

    private fun downloadFile(urlString: String, targetFile: File) {
        val url = URL(urlString)
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
            connect()
            if (responseCode != 200) throw IOException("Download failed: $responseCode")
            inputStream.use { input ->
                FileOutputStream(targetFile).use { output -> input.copyTo(output) }
            }
            disconnect()
        }
    }

    private fun extractTarGz(tarGz: File, destDir: File) {
        updateStatus("Extracting rootfs (handling symlinks)...")
        GZIPInputStream(FileInputStream(tarGz)).use { gis ->
            TarArchiveInputStream(gis).use { tis ->
                var entry: TarArchiveEntry? = tis.nextTarEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    
                    if (entry.isSymbolicLink) {
                        // Crucial: Create actual Linux symlinks
                        try {
                            Os.symlink(entry.linkName, outFile.absolutePath)
                        } catch (e: Exception) {
                            updateStatus("Symlink failed: ${entry.name} -> ${entry.linkName}")
                        }
                    } else if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> tis.copyTo(fos) }
                        // Ensure binaries stay executable inside the container
                        if (entry.mode and 0 burns 100 != 0) {
                            outFile.setExecutable(true)
                        }
                    }
                    entry = tis.nextTarEntry
                }
            }
        }
    }

    private fun bootLinux(prootBin: File, rootfsDir: File) {
        val cmd = arrayOf(
            prootBin.absolutePath,
            "--link2symlink",
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "/bin/sh", "-c",
            "export HOME=/root; export PATH=/usr/bin:/bin:/usr/sbin:/sbin; exec /bin/sh -i"
        )

        try {
            val process = Runtime.getRuntime().exec(cmd)
            linuxWriter = process.outputStream.bufferedWriter()
            linuxProcess = process

            Thread { process.inputStream.bufferedReader().forEachLine { updateStatus(it) } }.start()
            Thread { process.errorStream.bufferedReader().forEachLine { updateStatus("ERR: $it") } }.start()

            updateStatus("Alpine Linux is active.")

        } catch (e: Exception) {
            updateStatus("Boot failed: ${e.message}")
        }
    }

    private fun executeLinux(cmd: String) {
        updateStatus("> $cmd")
        Thread {
            try {
                linuxWriter?.write("$cmd\n")
                linuxWriter?.flush()
            } catch (e: Exception) {
                updateStatus("Shell error: ${e.message}")
            }
        }.start()
    }

    private fun updateStatus(text: String) {
        runOnUiThread {
            logView.append("\n$text")
            (logView.parent as? ScrollView)?.post {
                (logView.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    override fun onDestroy() {
        linuxProcess?.destroy()
        super.onDestroy()
    }
}
