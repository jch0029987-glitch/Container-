package com.vphone.clone

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.*

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private var linuxWriter: BufferedWriter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)

        // Start Linux
        initOS()
    }

    private fun initOS() {
        val binDir = File(filesDir, "bin").apply { mkdirs() }
        val rootfsDir = File(filesDir, "rootfs")
        val qemuBin = File(binDir, "qemu-aarch64")
        val rootfsTar = File(filesDir, "rootfs.tar.gz")

        Thread {
            try {
                // Copy QEMU binary
                if (!qemuBin.exists()) {
                    assets.open("bin/qemu-aarch64").use { inp ->
                        qemuBin.outputStream().use { out -> inp.copyTo(out) }
                    }
                    qemuBin.setExecutable(true)
                }

                // Extract rootfs
                if (!rootfsDir.exists() || rootfsDir.list()?.isEmpty() == true) {
                    rootfsDir.mkdirs()
                    assets.open("rootfs.tar.gz").use { inp ->
                        rootfsTar.outputStream().use { out -> inp.copyTo(out) }
                    }

                    val tarProcess = Runtime.getRuntime().exec(
                        arrayOf("tar", "-xzf", rootfsTar.absolutePath, "-C", rootfsDir.absolutePath)
                    )
                    if (tarProcess.waitFor() != 0) throw Exception("Failed to extract rootfs")
                    rootfsTar.delete()
                }

                // Launch Alpine shell
                bootLinux(qemuBin, rootfsDir)

            } catch (e: Exception) {
                updateStatus("ERROR: ${e.message}")
            }
        }.start()
    }

    private fun bootLinux(qemuBin: File, rootfsDir: File) {
        val cmd = arrayOf(
            qemuBin.absolutePath,
            "-L", rootfsDir.absolutePath,
            "/bin/sh", "-c", "echo 'Alpine is running!' && exec /bin/sh"
        )

        val process = Runtime.getRuntime().exec(cmd)
        linuxWriter = process.outputStream.bufferedWriter()

        // Capture stdout
        Thread { process.inputStream.bufferedReader().forEachLine { updateStatus(it) } }.start()
        // Capture stderr
        Thread { process.errorStream.bufferedReader().forEachLine { updateStatus("ERR: $it") } }.start()
    }

    private fun updateStatus(text: String) {
        runOnUiThread {
            logView.append("\n$text")
        }
    }
}
