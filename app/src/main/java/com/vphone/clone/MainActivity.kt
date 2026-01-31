package com.vphone.clone

import android.content.res.AssetManager
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
    private external fun sendTouchEvent(x: Int, y: Int, action: Int)
    
    private var isRunning = false
    private var linuxWriter: BufferedWriter? = null
    private lateinit var logView: TextView
    private lateinit var consoleLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        consoleLayout = findViewById(R.id.terminalConsole)
        val surfaceView = findViewById<SurfaceView>(R.id.phoshSurface)
        surfaceView.holder.addCallback(this)

        val btnToggle = findViewById<Button>(R.id.btnToggle)
        val cmdInput = findViewById<EditText>(R.id.cmdInput)

        btnToggle.setOnClickListener {
            consoleLayout.visibility = if (consoleLayout.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        cmdInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val cmd = v.text.toString()
                executeLinux(cmd)
                (v as EditText).setText("")
                true
            } else false
        }

        // Debug: List what's actually in assets
        debugAssets()
        initOS()
    }

    private fun debugAssets() {
        val list = assets.list("")?.joinToString(", ") ?: "None"
        val binList = assets.list("bin")?.joinToString(", ") ?: "None"
        updateStatus("Found in assets: $list")
        updateStatus("Found in assets/bin: $binList")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.toInt()
        val y = event.y.toInt()
        val action = when (event.action) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_MOVE -> 1
            MotionEvent.ACTION_UP -> 2
            else -> -1
        }
        if (action != -1) sendTouchEvent(x, y, action)
        return true
    }

    private fun initOS() {
        val binDir = File(filesDir, "bin").apply { mkdirs() }
        val rootfsDir = File(filesDir, "rootfs")
        val prootBin = File(binDir, "proot")

        Thread {
            try {
                // 1. Extract PRoot Engine
                if (!prootBin.exists()) {
                    updateStatus("Installing PRoot...")
                    // Using ACCESS_STREAMING to handle larger binary files safely
                    assets.open("bin/proot", AssetManager.ACCESS_STREAMING).use { inp ->
                        prootBin.outputStream().use { out -> inp.copyTo(out) }
                    }
                    prootBin.setExecutable(true)
                }

                // 2. Extract Rootfs
                if (!rootfsDir.exists() || rootfsDir.list()?.isEmpty() == true) {
                    rootfsDir.mkdirs()
                    updateStatus("Extracting Linux OS... This takes a moment.")
                    
                    val tarFile = File(filesDir, "rootfs.tar.gz")
                    
                    // The specific fix: Ensuring we catch the exact error if it fails here
                    try {
                        assets.open("rootfs.tar.gz", AssetManager.ACCESS_STREAMING).use { inp ->
                            tarFile.outputStream().use { out -> inp.copyTo(out) }
                        }
                    } catch (e: Exception) {
                        throw Exception("Asset File Error: ${e.localizedMessage}")
                    }

                    val process = Runtime.getRuntime().exec("tar -xzf ${tarFile.absolutePath} -C ${rootfsDir.absolutePath}")
                    val exitCode = process.waitFor()
                    if (exitCode != 0) throw Exception("Tar failed with code $exitCode")
                    
                    tarFile.delete()
                }

                updateStatus("Booting Phosh Display...")
                bootLinux()

            } catch (e: Exception) {
                updateStatus("CRITICAL ERROR: ${e.message}")
                e.printStackTrace()
            }
        }.start()
    }

    private fun bootLinux() {
        val proot = File(filesDir, "bin/proot").absolutePath
        val guest = File(filesDir, "rootfs").absolutePath
        
        val bootCmd = "$proot -r $guest -0 -b /dev -b /proc -b /sys /bin/sh -c " +
                      "\"apk update && apk add phosh xvfb xdotool; " +
                      "Xvfb :1 -screen 0 1080x1920x32 -fbdir /tmp & " +
                      "export DISPLAY=:1; phosh\""

        val process = Runtime.getRuntime().exec(bootCmd)
        linuxWriter = process.outputStream.bufferedWriter()

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
            (logView.parent as? ScrollView)?.post { 
                (logView.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN) 
            }
        }
    }

    private fun executeLinux(cmd: String) {
        Thread {
            try {
                linuxWriter?.write("$cmd\n")
                linuxWriter?.flush()
            } catch (e: Exception) {
                updateStatus("Shell Error: ${e.message}")
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

    override fun surfaceDestroyed(h: SurfaceHolder) { isRunning = false }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
}
