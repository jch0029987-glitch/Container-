package com.vphone.clone

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.antlersoft.android.bc.RemoteCanvas
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var vncCanvas: RemoteCanvas
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vncCanvas = findViewById(R.id.vnc_view)
        statusText = findViewById(R.id.status_text)

        // Run the boot sequence
        bootSequence()
    }

    private fun bootSequence() {
        Thread {
            try {
                // 1. Prepare files
                updateStatus("Setting up environment...")
                prepareBinaries()

                // 2. Start the Virtual Engine
                updateStatus("Starting Virtual Engine...")
                startEngine()

                // 3. Connect the Screen (Delay allows server to warm up)
                Handler(Looper.getMainLooper()).postDelayed({
                    updateStatus("Connecting Display...")
                    try {
                        // Standard VNC port for display :1 is 5901
                        vncCanvas.connect("127.0.0.1", 5901, "vphone_pass")
                        statusText.text = "" // Clear text on success
                    } catch (e: Exception) {
                        updateStatus("Display Error: ${e.message}")
                    }
                }, 4000)

            } catch (e: Exception) {
                updateStatus("Boot Failed: ${e.message}")
            }
        }.start()
    }

    private fun prepareBinaries() {
        val binDir = File(filesDir, "bin")
        if (!binDir.exists()) binDir.mkdirs()

        // Copy proot from assets to internal storage so it can be executed
        val prootFile = File(binDir, "proot")
        assets.open("bin/proot").use { input ->
            FileOutputStream(prootFile).use { output ->
                input.copyTo(output)
            }
        }
        prootFile.setExecutable(true)
    }

    private fun startEngine() {
        val prootPath = File(filesDir, "bin/proot").absolutePath
        val guestPath = File(filesDir, "guest").absolutePath
        
        // Basic PRoot command to start the guest environment
        val pb = ProcessBuilder(
            prootPath,
            "-r", guestPath,
            "-0", // Fake root
            "-b", "/dev",
            "-b", "/proc",
            "/usr/bin/vncserver", ":1"
        )
        pb.directory(filesDir)
        pb.start()
    }

    private fun updateStatus(text: String) {
        Handler(Looper.getMainLooper()).post {
            statusText.text = text
        }
    }
}
