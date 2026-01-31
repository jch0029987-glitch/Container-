#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>

extern "C" JNIEXPORT void JNICALL
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
        if (ANativeWindow_lock(window, &buffer, nullptr) == 0) {
            // Read direct from Phosh virtual display
            read(fd, buffer.bits, buffer.width * buffer.height * 4);
            ANativeWindow_unlockAndPost(window);
        }
        ANativeWindow_release(window);
    }
    close(fd);
    env->ReleaseStringUTFChars(fbPath, path);
}
