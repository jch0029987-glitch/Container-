#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <stdlib.h>
#include <stdio.h>

extern "C" {

/**
 * Renders the Linux Framebuffer onto the Android Surface.
 * Standard path: /data/data/com.vphone.clone/files/rootfs/tmp/fb0
 */
JNIEXPORT void JNICALL
Java_com_vphone_clone_MainActivity_renderFrame(JNIEnv* env, jobject obj, jobject surface, jstring fbPath) {
    const char* path = env->GetStringUTFChars(fbPath, nullptr);
    int fd = open(path, O_RDONLY);
    
    if (fd < 0) {
        env->ReleaseStringUTFChars(fbPath, path);
        return;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window) {
        ANativeWindow_Buffer buffer;
        // Lock the surface to get the pixel buffer
        if (ANativeWindow_lock(window, &buffer, nullptr) == 0) {
            // Read raw pixels directly into the GPU-linked surface buffer
            // Framebuffer is usually 1080 * 1920 * 4 bytes (RGBA)
            read(fd, buffer.bits, buffer.width * buffer.height * 4);
            ANativeWindow_unlockAndPost(window);
        }
        ANativeWindow_release(window);
    }
    
    close(fd);
    env->ReleaseStringUTFChars(fbPath, path);
}

/**
 * Translates Android Touch Events into Linux Mouse Events using xdotool.
 * Action codes: 0 = Down, 1 = Move, 2 = Up
 */
JNIEXPORT void JNICALL
Java_com_vphone_clone_MainActivity_sendTouchEvent(JNIEnv* env, jobject obj, jint x, jint y, jint action) {
    char cmd[128];
    
    switch(action) {
        case 0: // ACTION_DOWN
            // Move mouse to position and simulate left-click hold
            sprintf(cmd, "export DISPLAY=:1; xdotool mousemove %d %d mousedown 1", x, y);
            break;
        case 1: // ACTION_MOVE
            // Simply update mouse position
            sprintf(cmd, "export DISPLAY=:1; xdotool mousemove %d %d", x, y);
            break;
        case 2: // ACTION_UP
            // Release the left-click
            sprintf(cmd, "export DISPLAY=:1; xdotool mouseup 1");
            break;
        default:
            return;
    }
    
    // Execute the command in the background
    // system() is fine here since we are interacting with the Xvfb session
    system(cmd);
}

}
