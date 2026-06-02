#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#define ERROR_BUFFER_SIZE 4096

extern int32_t souz_macos_speech_authorization_status(void);
extern int32_t souz_macos_speech_request_authorization_if_needed(void);
extern int32_t souz_macos_speech_has_usage_description(void);
extern void souz_macos_speech_cancel_recognition(void);
extern char *souz_macos_speech_recognize_wav(
    const char *path,
    const char *locale,
    char *errorBuffer,
    int32_t errorBufferSize
);
extern int32_t souz_macos_live_speech_is_supported(const char *locale);
extern int32_t souz_macos_live_speech_prepare_assets(
    const char *locale,
    char *errorBuffer,
    int32_t errorBufferSize
);
extern int64_t souz_macos_live_speech_start(
    const char *locale,
    char *errorBuffer,
    int32_t errorBufferSize
);
extern int32_t souz_macos_live_speech_accept_pcm(
    int64_t sessionId,
    const int8_t *audio,
    int32_t audioSize,
    int32_t sampleRateHz,
    int32_t channels,
    int32_t bitsPerSample,
    char *errorBuffer,
    int32_t errorBufferSize
);
extern char *souz_macos_live_speech_poll_events(
    int64_t sessionId,
    char *errorBuffer,
    int32_t errorBufferSize
);
extern char *souz_macos_live_speech_finalize_and_finish(
    int64_t sessionId,
    char *errorBuffer,
    int32_t errorBufferSize
);
extern int32_t souz_macos_live_speech_cancel(
    int64_t sessionId,
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
Java_ru_souz_service_speech_MacOsSpeechBridge_hasSpeechRecognitionUsageDescriptionNative(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return (jboolean)(souz_macos_speech_has_usage_description() != 0);
}

JNIEXPORT jint JNICALL
Java_ru_souz_service_speech_MacOsSpeechBridge_authorizationStatusNative(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return (jint)souz_macos_speech_authorization_status();
}

JNIEXPORT void JNICALL
Java_ru_souz_service_speech_MacOsSpeechBridge_requestAuthorizationIfNeededNative(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    (void)souz_macos_speech_request_authorization_if_needed();
}

JNIEXPORT void JNICALL
Java_ru_souz_service_speech_MacOsSpeechBridge_cancelRecognitionNative(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    souz_macos_speech_cancel_recognition();
}

JNIEXPORT jstring JNICALL
Java_ru_souz_service_speech_MacOsSpeechBridge_recognizeWavNative(
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

JNIEXPORT jboolean JNICALL
Java_ru_souz_service_speech_MacOsSpeechBridge_liveIsSupportedNative(
    JNIEnv *env,
    jobject thiz,
    jstring locale
) {
    (void)thiz;

    const char *locale_utf = (*env)->GetStringUTFChars(env, locale, NULL);
    if (locale_utf == NULL) {
        return JNI_FALSE;
    }

    int32_t result = souz_macos_live_speech_is_supported(locale_utf);
    (*env)->ReleaseStringUTFChars(env, locale, locale_utf);
    return (jboolean)(result != 0);
}

JNIEXPORT void JNICALL
Java_ru_souz_service_speech_MacOsSpeechBridge_livePrepareAssetsNative(
    JNIEnv *env,
    jobject thiz,
    jstring locale
) {
    (void)thiz;

    const char *locale_utf = (*env)->GetStringUTFChars(env, locale, NULL);
    if (locale_utf == NULL) {
        return;
    }

    char error_buffer[ERROR_BUFFER_SIZE];
    error_buffer[0] = '\0';

    int32_t ok = souz_macos_live_speech_prepare_assets(
        locale_utf,
        error_buffer,
        ERROR_BUFFER_SIZE
    );

    (*env)->ReleaseStringUTFChars(env, locale, locale_utf);

    if (ok == 0) {
        throw_illegal_state(
            env,
            error_buffer[0] != '\0' ? error_buffer : "Local macOS live speech transcription failed to prepare assets."
        );
    }
}

JNIEXPORT jlong JNICALL
Java_ru_souz_service_speech_MacOsSpeechBridge_liveStartNative(
    JNIEnv *env,
    jobject thiz,
    jstring locale
) {
    (void)thiz;

    const char *locale_utf = (*env)->GetStringUTFChars(env, locale, NULL);
    if (locale_utf == NULL) {
        return 0;
    }

    char error_buffer[ERROR_BUFFER_SIZE];
    error_buffer[0] = '\0';

    int64_t session_id = souz_macos_live_speech_start(
        locale_utf,
        error_buffer,
        ERROR_BUFFER_SIZE
    );

    (*env)->ReleaseStringUTFChars(env, locale, locale_utf);

    if (session_id <= 0) {
        throw_illegal_state(
            env,
            error_buffer[0] != '\0' ? error_buffer : "Local macOS live speech transcription failed to start."
        );
        return 0;
    }

    return (jlong)session_id;
}

JNIEXPORT void JNICALL
Java_ru_souz_service_speech_MacOsSpeechBridge_liveAcceptPcmNative(
    JNIEnv *env,
    jobject thiz,
    jlong sessionId,
    jbyteArray audio,
    jint sampleRateHz,
    jint channels,
    jint bitsPerSample
) {
    (void)thiz;

    if (audio == NULL) {
        throw_illegal_state(env, "Local macOS live speech transcription received null audio.");
        return;
    }

    jsize audio_size = (*env)->GetArrayLength(env, audio);
    jbyte *audio_bytes = (*env)->GetByteArrayElements(env, audio, NULL);
    if (audio_bytes == NULL) {
        return;
    }

    char error_buffer[ERROR_BUFFER_SIZE];
    error_buffer[0] = '\0';

    int32_t ok = souz_macos_live_speech_accept_pcm(
        (int64_t)sessionId,
        (const int8_t *)audio_bytes,
        (int32_t)audio_size,
        (int32_t)sampleRateHz,
        (int32_t)channels,
        (int32_t)bitsPerSample,
        error_buffer,
        ERROR_BUFFER_SIZE
    );

    (*env)->ReleaseByteArrayElements(env, audio, audio_bytes, JNI_ABORT);

    if (ok == 0) {
        throw_illegal_state(
            env,
            error_buffer[0] != '\0' ? error_buffer : "Local macOS live speech transcription failed to accept audio."
        );
    }
}

JNIEXPORT jstring JNICALL
Java_ru_souz_service_speech_MacOsSpeechBridge_livePollEventsNative(
    JNIEnv *env,
    jobject thiz,
    jlong sessionId
) {
    (void)thiz;

    char error_buffer[ERROR_BUFFER_SIZE];
    error_buffer[0] = '\0';

    char *result = souz_macos_live_speech_poll_events(
        (int64_t)sessionId,
        error_buffer,
        ERROR_BUFFER_SIZE
    );

    if (result == NULL) {
        throw_illegal_state(
            env,
            error_buffer[0] != '\0' ? error_buffer : "Local macOS live speech transcription failed to poll events."
        );
        return NULL;
    }

    jstring events = (*env)->NewStringUTF(env, result);
    souz_macos_speech_string_free(result);
    return events;
}

JNIEXPORT jstring JNICALL
Java_ru_souz_service_speech_MacOsSpeechBridge_liveFinalizeAndFinishNative(
    JNIEnv *env,
    jobject thiz,
    jlong sessionId
) {
    (void)thiz;

    char error_buffer[ERROR_BUFFER_SIZE];
    error_buffer[0] = '\0';

    char *result = souz_macos_live_speech_finalize_and_finish(
        (int64_t)sessionId,
        error_buffer,
        ERROR_BUFFER_SIZE
    );

    if (result == NULL) {
        throw_illegal_state(
            env,
            error_buffer[0] != '\0' ? error_buffer : "Local macOS live speech transcription failed to finish."
        );
        return NULL;
    }

    jstring events = (*env)->NewStringUTF(env, result);
    souz_macos_speech_string_free(result);
    return events;
}

JNIEXPORT void JNICALL
Java_ru_souz_service_speech_MacOsSpeechBridge_liveCancelNative(
    JNIEnv *env,
    jobject thiz,
    jlong sessionId
) {
    (void)thiz;

    char error_buffer[ERROR_BUFFER_SIZE];
    error_buffer[0] = '\0';

    int32_t ok = souz_macos_live_speech_cancel(
        (int64_t)sessionId,
        error_buffer,
        ERROR_BUFFER_SIZE
    );

    if (ok == 0) {
        throw_illegal_state(
            env,
            error_buffer[0] != '\0' ? error_buffer : "Local macOS live speech transcription failed to cancel."
        );
    }
}
