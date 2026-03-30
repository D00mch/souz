#pragma once

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*souz_llama_stream_callback)(const char * event_json, void * user_data);

const char * souz_llama_healthcheck(void);
void * souz_llama_runtime_create(const char * config_json, char * error_buffer, size_t error_buffer_size);
void souz_llama_runtime_destroy(void * runtime);

void * souz_llama_model_load(void * runtime, const char * request_json, char * error_buffer, size_t error_buffer_size);
void souz_llama_model_unload(void * runtime, void * model);

const char * souz_llama_generate(void * runtime, void * model, const char * request_json, char * error_buffer, size_t error_buffer_size);
const char * souz_llama_generate_stream(
    void * runtime,
    void * model,
    const char * request_json,
    souz_llama_stream_callback callback,
    void * user_data,
    char * error_buffer,
    size_t error_buffer_size
);

void souz_llama_cancel(void * runtime);
void souz_llama_string_free(const char * ptr);

#ifdef __cplusplus
}
#endif
