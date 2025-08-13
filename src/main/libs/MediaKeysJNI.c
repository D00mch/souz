#include <jni.h>
#include "MediaKeysNative.h"
#include <stdint.h>

// Объявление Swift-функции
extern void sendMediaKey(uint32_t keyCode);

JNIEXPORT void JNICALL Java_MediaKeysNative_sendMediaKey(
    JNIEnv *env,
    jobject obj,
    jint keyCode) {

    sendMediaKey((uint32_t)keyCode);
}