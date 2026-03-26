package com.vphone.clone

import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.*
import java.net.URL
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var logView: TextView
    private lateinit var cmdInput: EditText
    private lateinit var surfaceView: SurfaceView
    
    private var linuxWriter: BufferedWriter? = null
    private var linuxProcess: Process? = null
    private var isRendering = false

    companion object {
        init {
            // Load your custom JNI rendering library
            System.loadLibrary("vphone_clone")
        }
    }

    // JNI methods from your C code
    private external fun renderFrame(surface: Surface, fbPath: String)
    private external fun sendTouchEvent(x: Int, y: Int, action: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        cmdInput = findViewById(R.id.cmdInput)
        surfaceView = findViewById(R.id.linuxSurface)
        
        surfaceView.holder.addCallback(this)

        cmdInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                executeLinux((v as EditText).text.toString())
                v.setText("")
                true
            } else false
        }

        initOS()
    }

    private fun initOS() {
        // This points to /data/app/~~.../lib/arm64 (or x86_64)
        // Android automatically extracts libproot.so here during install
        val libDir = applicationInfo.nativeLibraryDir
        val prootBin = File(libDir, "libproot.so")
        
        val rootfsDir = File(filesDir, "rootfs")
        val rootfsTar = File(filesDir, "rootfs.tar.gz")

        Thread {
            try {
                // 1. Verify PRoot exists
                if (!prootBin.exists()) {
                    updateStatus("ERROR: libproot.so not found at: ${prootBin.absolutePath}")
                    // List files in lib dir for debugging
                    val files = File(libDir).list()?.joinToString(", ")
                    updateStatus("Available libs: $files")
                    return@Thread
                }

                // 2. Handle Rootfs
                if (!rootfsDir.exists() || rootfsDir.list().isNullOrEmpty()) {
                    if (!rootfsTar.exists()) {
                        updateStatus("Downloading Alpine...")
                        downloadFile("https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz", rootfsTar)
                    }
                    updateStatus("Extracting Rootfs...")
                    extractTarGz(rootfsTar, rootfsDir)
                }

                updateStatus("Starting Linux Environment...")
                bootLinux(prootBin, rootfsDir)

            } catch (e: Exception) {
                updateStatus("Init Error: ${e.localizedMessage}")
            }
        }.start()
    }

    private fun bootLinux(prootBin: File, rootfsDir: File) {
        // Essential environment variables for PRoot and Alpine
        val env = arrayOf(
            "TERM=xterm-256color",
            "HOME=/root",
            "PATH=/usr/bin:/bin:/usr/sbin:/sbin",
            "LD_LIBRARY_PATH=${applicationInfo.nativeLibraryDir}"
        )

        // The command must use the absolute path to libproot.so
        val cmd = arrayOf(
            prootBin.absolutePath,
            "-0",                     // Fake root (rootless)
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", "/root",            // Set working directory
            "/bin/sh", "-i"
        )

        try {
            linuxProcess = Runtime.getRuntime().exec(cmd, env, rootfsDir)
            linuxWriter = linuxProcess?.outputStream?.bufferedWriter()

            // Handle output and error streams
            Thread { linuxProcess?.inputStream?.bufferedReader()?.forEachLine { updateStatus(it) } }.start()
            Thread { linuxProcess?.errorStream?.bufferedReader()?.forEachLine { updateStatus("ERR: $it") } }.start()

            updateStatus("Alpine Shell Active")
            
            // Optional: Start Xvfb if you've installed it in the rootfs
            // executeLinux("Xvfb :1 -screen 0 1080x1920x24 -fbdir /tmp &")

        } catch (e: Exception) {
            updateStatus("Boot Failed: ${e.message}")
        }
    }

    // --- Rendering Loop ---
    override fun surfaceCreated(holder: SurfaceHolder) {
        isRendering = true
        // Path to the framebuffer file created by Xvfb inside Alpine
        val fbPath = File(filesDir, "rootfs/tmp/Xvfb_screen0").absolutePath
        
        Thread {
            while (isRendering) {
                if (File(fbPath).exists()) {
                    renderFrame(holder.surface, fbPath)
                }
                Thread.sleep(32) // Aim for ~30 FPS
            }
        }.start()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) { isRendering = false }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}

    // --- Helpers ---
    private fun executeLinux(cmd: String) {
        Thread {
            try {
                linuxWriter?.write("$cmd\n")
                linuxWriter?.flush()
            } catch (e: Exception) { e.printStackTrace() }
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

    private fun downloadFile(url: String, target: File) {
        URL(url).openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    private fun extractTarGz(tarGz: File, dest: File) {
        dest.mkdirs()
        GZIPInputStream(FileInputStream(tarGz)).use { gis ->
            TarArchiveInputStream(gis).use { tis ->
                var entry: TarArchiveEntry?
                while (tis.nextTarEntry.also { entry = it } != null) {
                    val outFile = File(dest, entry!!.name)
                    if (entry!!.isDirectory) outFile.mkdirs()
                    else {
                        outFile.parentFile?.mkdirs()
                        tis.copyTo(outFile.outputStream())
                        if (entry!!.mode and 0x40 != 0) outFile.setExecutable(true, false)
                    }
                }
            }
        }
    }
}
