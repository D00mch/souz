#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#define ERROR_BUFFER_SIZE 4096

extern int32_t souz_macos_speech_authorization_status(void);
extern int32_t souz_macos_speech_request_authorization_if_needed(void);
extern int32_t souz_macos_speech_has_usage_description(void);
extern char *souz_macos_speech_recognize_wav(
    const char *path,
    const char *locale,
    char *errorBuffer,
    int32_t errorBufferSize
);
extern void souz_macos_speech_string_free(char *value);

static void throw_illegal_state(JNIEnv *env, const char *message) {
    jclass exception_class = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (exception_class != NULL) {
        (*env)->ThrowNew(env, exception_class, message != NULL ? message : "Local macOS speech bridge failed.");
    }
}

JNIEXPORT jboolean JNICALL
Java_ru_souz_ui_main_usecases_MacOsSpeechBridge_hasSpeechRecognitionUsageDescriptionNative(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return (jboolean)(souz_macos_speech_has_usage_description() != 0);
}

JNIEXPORT jint JNICALL
Java_ru_souz_ui_main_usecases_MacOsSpeechBridge_authorizationStatusNative(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return (jint)souz_macos_speech_authorization_status();
}

JNIEXPORT void JNICALL
Java_ru_souz_ui_main_usecases_MacOsSpeechBridge_requestAuthorizationIfNeededNative(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    (void)souz_macos_speech_request_authorization_if_needed();
}

JNIEXPORT jstring JNICALL
Java_ru_souz_ui_main_usecases_MacOsSpeechBridge_recognizeWavNative(
    JNIEnv *env,
    jobject thiz,
    jstring path,
    jstring locale
) {
    (void)thiz;

    const char *path_utf = (*env)->GetStringUTFChars(env, path, NULL);
    if (path_utf == NULL) {
        return NULL;
    }

    const char *locale_utf = (*env)->GetStringUTFChars(env, locale, NULL);
    if (locale_utf == NULL) {
        (*env)->ReleaseStringUTFChars(env, path, path_utf);
        return NULL;
    }

    char error_buffer[ERROR_BUFFER_SIZE];
    error_buffer[0] = '\0';

    char *result = souz_macos_speech_recognize_wav(
        path_utf,
        locale_utf,
        error_buffer,
        ERROR_BUFFER_SIZE
    );

    (*env)->ReleaseStringUTFChars(env, path, path_utf);
    (*env)->ReleaseStringUTFChars(env, locale, locale_utf);

    if (result == NULL) {
        throw_illegal_state(env, error_buffer[0] != '\0' ? error_buffer : "Local macOS speech recognition failed.");
        return NULL;
    }

    jstring recognized = (*env)->NewStringUTF(env, result);
    souz_macos_speech_string_free(result);
    return recognized;
}
