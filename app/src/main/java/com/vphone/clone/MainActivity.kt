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

    // C++ Bridge Functions
    private external fun renderFrame(surface: Surface, fbPath: String)
    private external fun sendTouchEvent(x: Int, y: Int, action: Int)
    
    private var isRunning = false
    private var linuxWriter: BufferedWriter? = null
    private lateinit var logView: TextView
    private lateinit var consoleLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI Components
        logView = findViewById(R.id.logView)
        consoleLayout = findViewById(R.id.terminalConsole)
        val surfaceView = findViewById<SurfaceView>(R.id.phoshSurface)
        surfaceView.holder.addCallback(this)

        val btnToggle = findViewById<Button>(R.id.btnToggle)
        val cmdInput = findViewById<EditText>(R.id.cmdInput)

        // Toggle Terminal Visibility
        btnToggle.setOnClickListener {
            consoleLayout.visibility = if (consoleLayout.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // Terminal Input Logic with Fixed Reference
        cmdInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val cmd = v.text.toString()
                executeLinux(cmd)
                // Explicitly cast to EditText and use setText to avoid StringBuilder ambiguity
                (v as EditText).setText("")
                true
            } else false
        }

        // Start the OS Management Lifecycle
        initOS()
    }

    // Capture Android Touch and Send to Linux
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.toInt()
        val y = event.y.toInt()
        val action = when (event.action) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_MOVE -> 1
            MotionEvent.ACTION_UP -> 2
            else -> -1
        }
        if (action != -1) {
            sendTouchEvent(x, y, action)
        }
        return true
    }

    private fun initOS() {
        val binDir = File(filesDir, "bin").apply { mkdirs() }
        val rootfsDir = File(filesDir, "rootfs")
        val prootBin = File(binDir, "proot")

        Thread {
            try {
                // 1. Extract PRoot Engine if missing
                if (!prootBin.exists()) {
                    updateStatus("Extracting PRoot engine...")
                    this@MainActivity.assets.open("bin/proot").use { inp ->
                        prootBin.outputStream().use { out -> inp.copyTo(out) }
                    }
                    prootBin.setExecutable(true)
                }

                // 2. Extract Rootfs if missing
                if (!rootfsDir.exists() || rootfsDir.list()?.isEmpty() == true) {
                    rootfsDir.mkdirs()
                    updateStatus("Extracting Linux Rootfs (First run only)...")
                    
                    val tarFile = File(filesDir, "rootfs.tar.gz")
                    this@MainActivity.assets.open("rootfs.tar.gz").use { inp ->
                        tarFile.outputStream().use { out -> inp.copyTo(out) }
                    }

                    val process = Runtime.getRuntime().exec("tar -xzf ${tarFile.absolutePath} -C ${rootfsDir.absolutePath}")
                    process.waitFor()
                    tarFile.delete()
                }

                updateStatus("OS Files Ready. Booting Phosh...")
                bootLinux()

            } catch (e: Exception) {
                updateStatus("System Error: ${e.message}")
            }
        }.start()
    }

    private fun bootLinux() {
        val proot = File(filesDir, "bin/proot").absolutePath
        val guest = File(filesDir, "rootfs").absolutePath
        
        // Automated Boot Command: Installs required tools and starts Phosh
        val bootCmd = "$proot -r $guest -0 -b /dev -b /proc -b /sys /bin/sh -c " +
                      "\"apk update && apk add phosh xvfb xdotool; " +
                      "Xvfb :1 -screen 0 1080x1920x32 -fbdir /tmp & " +
                      "export DISPLAY=:1; phosh\""

        val process = Runtime.getRuntime().exec(bootCmd)
        linuxWriter = process.outputStream.bufferedWriter()

        // Read and Display Linux System Logs
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
                updateStatus("Terminal Error: ${e.message}")
            }
        }.start()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isRunning = true
        val fbPath = File(filesDir, "rootfs/tmp/fb0").absolutePath
        
        Thread {
            while (isRunning) {
                if (File(fbPath).exists()) {
                    renderFrame(holder.surface, fbPath)
                }
                Thread.sleep(16) 
            }
        }.start()
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        isRunning = false
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
}
