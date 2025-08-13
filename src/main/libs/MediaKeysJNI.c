#include <jni.h>

// Declarations of Swift functions compiled into the library
extern void playPause(void);
extern void nextTrack(void);
extern void previousTrack(void);

JNIEXPORT void JNICALL Java_com_dumch_libs_MediaKeysNative_sendMediaKeyEvent
  (JNIEnv *env, jobject obj, jint keyCode) {
    switch (keyCode) {
        case 16:
            playPause();
            break;
        case 17:
            nextTrack();
            break;
        case 18:
            previousTrack();
            break;
        default:
            break;
    }
}

