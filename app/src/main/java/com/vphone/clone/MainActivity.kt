package com.vphone.clone

import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    // 1. Load the C++ Engine
    companion object {
        init {
            System.loadLibrary("vphone-engine")
        }
    }

    // 2. The link to your C++ code
    private external fun renderFrame(
        surface: Surface, 
        pixels: IntArray, 
        width: Int, 
        height: Int
    )

    private var isRunning = false
    private var renderThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Use a SurfaceView for direct-to-GPU performance
        val surfaceView = SurfaceView(this)
        setContentView(surfaceView)
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isRunning = true
        
        // Define our "Video Card" resolution
        val width = 1080
        val height = 1920
        
        // This array holds the pixels. 
        // 0xFF00FF00 is Green (Alpha, Red, Green, Blue)
        val pixels = IntArray(width * height) { 0xFF00FF00.toInt() }

        // 3. The Render Loop
        renderThread = Thread {
            while (isRunning) {
                // Push the pixels to the C++ engine
                renderFrame(holder.surface, pixels, width, height)
                
                // Keep it at roughly 60 FPS
                Thread.sleep(16)
            }
        }
        renderThread?.start()
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}

    override fun surfaceDestroyed(h: SurfaceHolder) {
        isRunning = false
        renderThread?.join()
    }
}
