package com.vphone.clone

import android.os.Bundle
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.*

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        init {
            System.loadLibrary("vphone-engine")
        }
    }

    private external fun renderFrame(surface: Surface, fbPath: String)
    
    private var isRunning = false
    private var linuxWriter: BufferedWriter? = null
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        val surfaceView = findViewById<SurfaceView>(R.id.phoshSurface)
        surfaceView.holder.addCallback(this)

        val console = findViewById<LinearLayout>(R.id.terminalConsole)
        findViewById<Button>(R.id.btnToggle).setOnClickListener {
            console.visibility = if (console.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<EditText>(R.id.cmdInput).setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                executeLinux(v.text.toString())
                v.text.clear()
                true
            } else false
        }

        // Start the lifecycle: Extract -> Boot -> Render
        initOS()
    }

    private fun initOS() {
        val binDir = File(filesDir, "bin").apply { mkdirs() }
        val rootfsDir = File(filesDir, "rootfs")
        val prootBin = File(binDir, "proot")

        Thread {
            try {
                // 1. Extract PRoot Engine
                if (!prootBin.exists()) {
                    updateStatus("Extracting PRoot engine...")
                    assets.open("bin/proot").use { inp ->
                        prootBin.outputStream().use { out -> inp.copyTo(out) }
                    }
                    prootBin.setExecutable(true)
                }

                // 2. Extract Rootfs Manager
                if (!rootfsDir.exists() || rootfsDir.list()?.isEmpty() == true) {
                    rootfsDir.mkdirs()
                    updateStatus("Extracting Linux Rootfs (First run only)...")
                    
                    val tarFile = File(filesDir, "rootfs.tar.gz")
                    assets.open("rootfs.tar.gz").use { inp ->
                        tarFile.outputStream().use { out -> inp.copyTo(out) }
                    }

                    // Use Android's native tar to unpack
                    val process = Runtime.getRuntime().exec("tar -xzf ${tarFile.absolutePath} -C ${rootfsDir.absolutePath}")
                    process.waitFor()
                    tarFile.delete()
                }

                updateStatus("OS Ready. Initializing Phosh...")
                bootLinux()

            } catch (e: Exception) {
                updateStatus("Error: ${e.message}")
            }
        }.start()
    }

    private fun bootLinux() {
        val proot = File(filesDir, "bin/proot").absolutePath
        val guest = File(filesDir, "rootfs").absolutePath
        
        // Automated boot script: Updates, Installs Phosh, Starts Display
        val bootCmd = "$proot -r $guest -0 -b /dev -b /proc -b /sys /bin/sh -c " +
                      "\"apk update && apk add phosh xvfb; " +
                      "Xvfb :1 -screen 0 1080x1920x32 -fbdir /tmp & " +
                      "export DISPLAY=:1; phosh\""

        val process = Runtime.getRuntime().exec(bootCmd)
        linuxWriter = process.outputStream.bufferedWriter()

        // Stream Linux output to our logView
        Thread {
            val reader = process.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                updateStatus(line ?: "")
            }
        }.start()
    }

    private fun updateStatus(text: String) {
        runOnUiThread {
            logView.append("\n$text")
            // Auto-scroll to bottom
            val scroll = logView.parent as? ScrollView
            scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun executeLinux(cmd: String) {
        Thread {
            try {
                linuxWriter?.write("$cmd\n")
                linuxWriter?.flush()
            } catch (e: Exception) {
                updateStatus("Write Error: ${e.message}")
            }
        }.start()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isRunning = true
        val fbPath = File(filesDir, "rootfs/tmp/fb0").absolutePath
        
        Thread {
            while (isRunning) {
                // If the framebuffer file exists, render it
                if (File(fbPath).exists()) {
                    renderFrame(holder.surface, fbPath)
                }
                Thread.sleep(16) // ~60fps
            }
        }.start()
    }

    override fun surfaceDestroyed(h: SurfaceHolder) { isRunning = false }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
}
