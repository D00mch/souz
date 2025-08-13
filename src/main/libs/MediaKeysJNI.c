#include <jni.h>

// Declarations of Swift functions compiled into the library
extern void playPause(void);
extern void nextTrack(void);
extern void previousTrack(void);

JNIEXPORT void JNICALL Java_com_dumch_libs_MediaKeysNative_playPauseNative(JNIEnv *env, jobject obj) {
    playPause();
}

JNIEXPORT void JNICALL Java_com_dumch_libs_MediaKeysNative_nextTrackNative(JNIEnv *env, jobject obj) {
    nextTrack();
}

JNIEXPORT void JNICALL Java_com_dumch_libs_MediaKeysNative_previousTrackNative(JNIEnv *env, jobject obj) {
    previousTrack();
}

