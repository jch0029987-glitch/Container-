package com.vphone.clone

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Start the OS in a background thread
        Thread {
            bootOS()
        }.start()
    }

    private fun bootOS() {
        val appFiles = filesDir.absolutePath
        val proot = "$appFiles/proot"
        val guestDir = "$appFiles/guest"

        // Command to start the virtual environment
        val builder = ProcessBuilder(
            proot,
            "-r", guestDir,
            "-0", // Fake root permissions
            "-b", "/dev",
            "-b", "/proc",
            "/bin/sh"
        )
        
        builder.redirectErrorStream(true)
        val process = builder.start()
        // Here we would pipe the output to our UI
    }
}
