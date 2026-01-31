#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cstring>

extern "C" JNIEXPORT void JNICALL
Java_com_vphone_clone_MainActivity_renderFrame(JNIEnv* env, jobject obj, jobject surface, jintArray pixels, jint width, jint height) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return;

    ANativeWindow_setBuffersGeometry(window, width, height, WINDOW_FORMAT_RGBA_8888);
    ANativeWindow_Buffer buffer;
    
    if (ANativeWindow_lock(window, &buffer, nullptr) == 0) {
        jint* data = env->GetIntArrayElements(pixels, nullptr);
        uint32_t* dest = (uint32_t*)buffer.bits;
        
        for (int y = 0; y < height; y++) {
            memcpy(dest + (y * buffer.stride), data + (y * width), width * 4);
        }

        env->ReleaseIntArrayElements(pixels, data, JNI_ABORT);
        ANativeWindow_unlockAndPost(window);
    }
    ANativeWindow_release(window);
}
