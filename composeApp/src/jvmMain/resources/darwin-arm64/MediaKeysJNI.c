#include <jni.h>
#include <stdint.h>

extern void sendMediaKeyEvent(uint32_t keyCode);

JNIEXPORT void JNICALL
Java_com_dumch_libs_MediaKeysNative_sendMediaKeyEvent
  (JNIEnv* env, jobject obj, jint keyCode) {
    sendMediaKeyEvent((uint32_t)keyCode);
}

