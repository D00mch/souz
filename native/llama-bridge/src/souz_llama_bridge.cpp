#include "souz_llama_bridge.h"

#include "ggml-backend.h"
#include "llama.h"

#include <nlohmann/json.hpp>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

using json = nlohmann::json;

namespace {

struct log_filter_state {
    std::atomic<int> min_level = GGML_LOG_LEVEL_ERROR;
    std::atomic<int> last_level = GGML_LOG_LEVEL_NONE;
};

struct backend_lifecycle_state {
    std::mutex mutex;
    size_t runtime_count = 0;
    bool initialized = false;
    std::vector<ggml_backend_reg_t> loaded_backends;
};

struct souz_model {
    llama_model * model = nullptr;
    std::string model_path;
};

using model_ptr = souz_model *;

struct cached_context_state {
    llama_context * ctx = nullptr;
    model_ptr model = nullptr;
    int context_size = 0;
    std::vector<llama_token> tokens;
};

struct souz_runtime {
    std::mutex mutex;
    std::atomic<bool> cancel_requested = false;
    cached_context_state cached_context;
};

struct generation_result {
    std::string text;
    std::string finish_reason = "stop";
    int prompt_tokens = 0;
    int completion_tokens = 0;
    int total_tokens = 0;
    int precached_prompt_tokens = 0;
    std::string error;
};

using runtime_ptr = souz_runtime *;
constexpr int DEFAULT_PROMPT_BATCH_SIZE = 512;

std::string format_generation_details(
    int context_size,
    int requested_max_tokens,
    int prompt_tokens,
    int max_tokens,
    int prompt_batch_size,
    size_t reused_prompt_tokens,
    size_t offset = 0,
    int chunk_size = 0,
    int decode_status = 0
) {
    std::ostringstream out;
    out << "context_size=" << context_size
        << ", requested_max_tokens=" << requested_max_tokens
        << ", prompt_tokens=" << prompt_tokens
        << ", max_tokens=" << max_tokens
        << ", prompt_batch_size=" << prompt_batch_size
        << ", reused_prompt_tokens=" << reused_prompt_tokens;
    if (chunk_size > 0) {
        out << ", offset=" << offset
            << ", chunk_size=" << chunk_size;
    }
    if (decode_status != 0) {
        out << ", decode_status=" << decode_status;
    }
    return out.str();
}

log_filter_state & native_log_state() {
    static log_filter_state state;
    return state;
}

backend_lifecycle_state & backend_lifecycle() {
    static backend_lifecycle_state state;
    return state;
}

char * duplicate_string(const std::string & value) {
    auto * buffer = static_cast<char *>(std::malloc(value.size() + 1));
    if (buffer == nullptr) {
        return nullptr;
    }
    std::memcpy(buffer, value.c_str(), value.size() + 1);
    return buffer;
}

void write_error(char * error_buffer, size_t error_buffer_size, const std::string & message) {
    if (error_buffer == nullptr || error_buffer_size == 0) {
        return;
    }
    std::snprintf(error_buffer, error_buffer_size, "%s", message.c_str());
}

runtime_ptr require_runtime(void * runtime) {
    if (runtime == nullptr) {
        throw std::runtime_error("Runtime handle is null.");
    }
    return static_cast<runtime_ptr>(runtime);
}

model_ptr require_model(void * model) {
    if (model == nullptr) {
        throw std::runtime_error("Model handle is null.");
    }
    return static_cast<model_ptr>(model);
}

void clear_cached_context(runtime_ptr runtime) {
    if (runtime == nullptr) {
        return;
    }
    if (runtime->cached_context.ctx != nullptr) {
        llama_free(runtime->cached_context.ctx);
        runtime->cached_context.ctx = nullptr;
    }
    runtime->cached_context.model = nullptr;
    runtime->cached_context.context_size = 0;
    runtime->cached_context.tokens.clear();
}

void configure_backend_environment() {
#if defined(__APPLE__)
    const char * explicit_no_residency = std::getenv("GGML_METAL_NO_RESIDENCY");
    const char * explicit_residency = std::getenv("SOUZ_LLAMA_METAL_RESIDENCY");
    const bool should_keep_residency =
        explicit_residency != nullptr &&
        (std::strcmp(explicit_residency, "1") == 0 || std::strcmp(explicit_residency, "true") == 0);

    if (explicit_no_residency == nullptr && !should_keep_residency) {
        setenv("GGML_METAL_NO_RESIDENCY", "1", 0);
    }
#endif
}

void retain_backend_lifecycle() {
    auto & state = backend_lifecycle();
    std::lock_guard<std::mutex> lock(state.mutex);

    if (!state.initialized) {
        configure_backend_environment();
        llama_backend_init();

        const size_t before_count = ggml_backend_reg_count();
        ggml_backend_load_all();
        const size_t after_count = ggml_backend_reg_count();

        state.loaded_backends.clear();
        for (size_t index = before_count; index < after_count; ++index) {
            state.loaded_backends.push_back(ggml_backend_reg_get(index));
        }
        state.initialized = true;
    }

    state.runtime_count += 1;
}

void release_backend_lifecycle() {
    auto & state = backend_lifecycle();
    std::lock_guard<std::mutex> lock(state.mutex);

    if (state.runtime_count == 0) {
        return;
    }

    state.runtime_count -= 1;
    if (state.runtime_count > 0 || !state.initialized) {
        return;
    }

    for (auto it = state.loaded_backends.rbegin(); it != state.loaded_backends.rend(); ++it) {
        if (*it != nullptr) {
            ggml_backend_unload(*it);
        }
    }
    state.loaded_backends.clear();
    llama_backend_free();
    state.initialized = false;
}

bool can_reuse_cached_context(
    runtime_ptr runtime,
    model_ptr model,
    int context_size,
    const std::vector<llama_token> & prompt_tokens,
    size_t & reused_token_count
) {
    reused_token_count = 0;
    if (runtime == nullptr) {
        return false;
    }
    const auto & cache = runtime->cached_context;
    if (cache.ctx == nullptr || cache.model != model || cache.context_size != context_size) {
        return false;
    }
    if (cache.tokens.empty() || prompt_tokens.size() < cache.tokens.size()) {
        return false;
    }
    if (!std::equal(cache.tokens.begin(), cache.tokens.end(), prompt_tokens.begin())) {
        return false;
    }
    reused_token_count = cache.tokens.size();
    return true;
}

bool abort_callback(void * data) {
    auto * flag = static_cast<std::atomic<bool> *>(data);
    return flag != nullptr && flag->load();
}

ggml_log_level parse_log_level(const char * value) {
    if (value == nullptr) {
        return GGML_LOG_LEVEL_ERROR;
    }

    std::string normalized(value);
    std::transform(normalized.begin(), normalized.end(), normalized.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });

    if (normalized == "none") {
        return GGML_LOG_LEVEL_NONE;
    }
    if (normalized == "debug") {
        return GGML_LOG_LEVEL_DEBUG;
    }
    if (normalized == "info") {
        return GGML_LOG_LEVEL_INFO;
    }
    if (normalized == "warn" || normalized == "warning") {
        return GGML_LOG_LEVEL_WARN;
    }
    return GGML_LOG_LEVEL_ERROR;
}

void llama_log_callback_filtered(enum ggml_log_level level, const char * text, void * user_data) {
    auto * state = static_cast<log_filter_state *>(user_data);
    if (state == nullptr || text == nullptr) {
        return;
    }

    if (level != GGML_LOG_LEVEL_CONT) {
        state->last_level.store(level);
    }

    const int effective_level = level == GGML_LOG_LEVEL_CONT
        ? state->last_level.load()
        : level;
    const int min_level = state->min_level.load();

    if (effective_level >= min_level && min_level != GGML_LOG_LEVEL_NONE) {
        std::fputs(text, stderr);
        std::fflush(stderr);
    }
}

std::string token_to_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(64);
    int written = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, true);
    if (written < 0) {
        buffer.resize(static_cast<size_t>(-written));
        written = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, true);
    }
    if (written < 0) {
        throw std::runtime_error("Failed to convert token to text.");
    }
    return std::string(buffer.data(), static_cast<size_t>(written));
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & prompt) {
    const int required = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, false, true);
    if (required <= 0) {
        throw std::runtime_error("Prompt tokenization failed.");
    }

    std::vector<llama_token> tokens(static_cast<size_t>(required));
    const int actual = llama_tokenize(
        vocab,
        prompt.c_str(),
        static_cast<int32_t>(prompt.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        false,
        true
    );
    if (actual < 0) {
        throw std::runtime_error("Prompt tokenization failed.");
    }
    tokens.resize(static_cast<size_t>(actual));
    return tokens;
}

bool trim_stop_sequence(std::string & text, const std::vector<std::string> & stop_sequences) {
    for (const auto & stop : stop_sequences) {
        if (stop.empty() || text.size() < stop.size()) {
            continue;
        }
        if (text.compare(text.size() - stop.size(), stop.size(), stop) == 0) {
            text.erase(text.size() - stop.size());
            return true;
        }
    }
    return false;
}

llama_sampler * create_sampler(const llama_vocab * vocab, const json & request) {
    const float temperature = request.value("temperature", 0.2f);
    const float top_p = request.value("top_p", 0.9f);
    const int top_k = request.value("top_k", 40);
    const uint32_t seed = request.value("seed", 42);
    const std::string grammar = request.value("grammar", "");

    auto params = llama_sampler_chain_default_params();
    auto * chain = llama_sampler_chain_init(params);

    if (!grammar.empty()) {
        auto * grammar_sampler = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (grammar_sampler == nullptr) {
            llama_sampler_free(chain);
            throw std::runtime_error("Failed to initialize strict JSON grammar for local inference.");
        }
        llama_sampler_chain_add(chain, grammar_sampler);
    }

    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
        return chain;
    }

    llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
    return chain;
}

generation_result generate_impl(
    runtime_ptr runtime,
    model_ptr model,
    const json & request,
    souz_llama_stream_callback callback,
    void * user_data
) {
    generation_result result;
    const std::string prompt = request.value("prompt", "");
    const int context_size = request.value("context_size", 4096);
    const int requested_max_tokens = request.value("max_tokens", 1024);
    const std::vector<std::string> stop_sequences = request.value("stop", std::vector<std::string>{});

    const llama_vocab * vocab = llama_model_get_vocab(model->model);
    auto prompt_tokens = tokenize(vocab, prompt);
    result.prompt_tokens = static_cast<int>(prompt_tokens.size());

    if (prompt_tokens.empty()) {
        throw std::runtime_error("Prompt is empty after tokenization.");
    }
    if (static_cast<int>(prompt_tokens.size()) >= context_size) {
        throw std::runtime_error("Prompt does not fit into the configured local context window.");
    }

    const int available_completion_tokens = context_size - static_cast<int>(prompt_tokens.size());
    if (available_completion_tokens <= 0) {
        throw std::runtime_error("Prompt does not leave room for any completion tokens.");
    }
    const int max_tokens = std::max(1, std::min(requested_max_tokens, available_completion_tokens));
    const int prompt_batch_size = std::max(32, std::min({context_size, DEFAULT_PROMPT_BATCH_SIZE, static_cast<int>(prompt_tokens.size())}));
    size_t reused_prompt_tokens = 0;

    llama_context * ctx = nullptr;
    if (can_reuse_cached_context(runtime, model, context_size, prompt_tokens, reused_prompt_tokens)) {
        ctx = runtime->cached_context.ctx;
        result.precached_prompt_tokens = static_cast<int>(reused_prompt_tokens);
    } else {
        clear_cached_context(runtime);

        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = static_cast<uint32_t>(context_size);
        ctx_params.n_batch = static_cast<uint32_t>(prompt_batch_size);
        ctx_params.n_ubatch = ctx_params.n_batch;
        ctx_params.n_threads = std::max(1u, std::thread::hardware_concurrency() / 2);
        ctx_params.n_threads_batch = std::max(1u, std::thread::hardware_concurrency());
        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
        ctx_params.offload_kqv = true;
        ctx_params.abort_callback = abort_callback;
        ctx_params.abort_callback_data = &runtime->cancel_requested;

        ctx = llama_init_from_model(model->model, ctx_params);
        if (ctx == nullptr) {
            throw std::runtime_error("Failed to initialize llama context.");
        }
        runtime->cached_context.ctx = ctx;
        runtime->cached_context.model = model;
        runtime->cached_context.context_size = context_size;
    }

    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(create_sampler(vocab, request), &llama_sampler_free);
    if (!sampler) {
        throw std::runtime_error("Failed to create sampler chain.");
    }

    for (size_t offset = reused_prompt_tokens; offset < prompt_tokens.size(); offset += static_cast<size_t>(prompt_batch_size)) {
        const int chunk_size = std::min<int>(prompt_batch_size, static_cast<int>(prompt_tokens.size() - offset));
        llama_batch batch = llama_batch_get_one(prompt_tokens.data() + offset, chunk_size);
        const int decode_status = llama_decode(ctx, batch);
        if (decode_status != 0) {
            if (runtime->cancel_requested.load()) {
                throw std::runtime_error("Generation cancelled.");
            }
            throw std::runtime_error(
                "Failed to decode the prompt: " +
                    format_generation_details(
                        context_size,
                        requested_max_tokens,
                        static_cast<int>(prompt_tokens.size()),
                        max_tokens,
                        prompt_batch_size,
                        reused_prompt_tokens,
                        offset,
                        chunk_size,
                        decode_status
                    )
            );
        }
    }

    std::vector<llama_token> generated_tokens;
    generated_tokens.reserve(static_cast<size_t>(max_tokens));

    for (int step = 0; step < max_tokens; ++step) {
        if (runtime->cancel_requested.load()) {
            throw std::runtime_error("Generation cancelled.");
        }

        llama_token token = llama_sampler_sample(sampler.get(), ctx, -1);
        llama_sampler_accept(sampler.get(), token);

        if (llama_vocab_is_eog(vocab, token)) {
            result.finish_reason = "stop";
            break;
        }

        const std::string piece = token_to_piece(vocab, token);
        result.text += piece;
        result.completion_tokens += 1;
        generated_tokens.push_back(token);

        if (callback != nullptr && !piece.empty()) {
            const auto event = json{
                {"type", "token"},
                {"text", piece},
            };
            const std::string payload = event.dump();
            callback(payload.c_str(), user_data);
        }

        if (trim_stop_sequence(result.text, stop_sequences)) {
            result.finish_reason = "stop";
            break;
        }

        llama_batch next_batch = llama_batch_get_one(&token, 1);
        const int decode_status = llama_decode(ctx, next_batch);
        if (decode_status != 0) {
            if (runtime->cancel_requested.load()) {
                throw std::runtime_error("Generation cancelled.");
            }
            throw std::runtime_error(
                "Failed to decode the next token: " +
                    format_generation_details(
                        context_size,
                        requested_max_tokens,
                        static_cast<int>(prompt_tokens.size()),
                        max_tokens,
                        prompt_batch_size,
                        reused_prompt_tokens,
                        prompt_tokens.size() + generated_tokens.size(),
                        1,
                        decode_status
                    )
            );
        }

        if (step == max_tokens - 1) {
            result.finish_reason = "length";
        }
    }

    runtime->cached_context.model = model;
    runtime->cached_context.context_size = context_size;
    runtime->cached_context.tokens = prompt_tokens;
    runtime->cached_context.tokens.insert(
        runtime->cached_context.tokens.end(),
        generated_tokens.begin(),
        generated_tokens.end()
    );
    result.total_tokens = result.prompt_tokens + result.completion_tokens;
    return result;
}

json healthcheck_json() {
    return json{
        {"ok", true},
        {"system_info", std::string(llama_print_system_info())},
    };
}

json result_json(const generation_result & result) {
    return json{
        {"text", result.text},
        {"finish_reason", result.finish_reason},
        {"prompt_tokens", result.prompt_tokens},
        {"completion_tokens", result.completion_tokens},
        {"total_tokens", result.total_tokens},
        {"precached_prompt_tokens", result.precached_prompt_tokens},
        {"error", result.error.empty() ? json(nullptr) : json(result.error)},
    };
}

} // namespace

extern "C" {

const char * souz_llama_healthcheck(void) {
    try {
        const std::string payload = healthcheck_json().dump();
        return duplicate_string(payload);
    } catch (const std::exception & error) {
        const auto payload = json{
            {"ok", false},
            {"error", error.what()},
        }.dump();
        return duplicate_string(payload);
    }
}

void * souz_llama_runtime_create(const char *, char * error_buffer, size_t error_buffer_size) {
    try {
        auto & log_state = native_log_state();
        log_state.min_level.store(parse_log_level(std::getenv("SOUZ_LLAMA_LOG_LEVEL")));
        log_state.last_level.store(GGML_LOG_LEVEL_NONE);
        llama_log_set(llama_log_callback_filtered, &log_state);
        retain_backend_lifecycle();
        return new souz_runtime();
    } catch (const std::exception & error) {
        release_backend_lifecycle();
        write_error(error_buffer, error_buffer_size, error.what());
        return nullptr;
    }
}

void souz_llama_runtime_destroy(void * runtime) {
    auto * runtime_handle = static_cast<runtime_ptr>(runtime);
    if (runtime_handle == nullptr) {
        return;
    }
    clear_cached_context(runtime_handle);
    delete runtime_handle;
    release_backend_lifecycle();
}

void * souz_llama_model_load(void * runtime, const char * request_json, char * error_buffer, size_t error_buffer_size) {
    try {
        auto * runtime_ptr = require_runtime(runtime);
        if (request_json == nullptr) {
            throw std::runtime_error("Model load request is null.");
        }

        std::lock_guard<std::mutex> lock(runtime_ptr->mutex);
        runtime_ptr->cancel_requested.store(false);
        clear_cached_context(runtime_ptr);

        const json request = json::parse(request_json);
        const std::string model_path = request.at("model_path").get<std::string>();

        llama_model_params params = llama_model_default_params();
        params.n_gpu_layers = request.value("gpu_layers", 99);
        params.use_mmap = request.value("use_mmap", true);
        params.use_mlock = request.value("use_mlock", false);

        llama_model * model = llama_model_load_from_file(model_path.c_str(), params);
        if (model == nullptr) {
            throw std::runtime_error("llama_model_load_from_file returned null.");
        }

        auto * handle = new souz_model();
        handle->model = model;
        handle->model_path = model_path;
        return handle;
    } catch (const std::exception & error) {
        write_error(error_buffer, error_buffer_size, error.what());
        return nullptr;
    }
}

void souz_llama_model_unload(void * runtime, void * model) {
    if (runtime == nullptr || model == nullptr) {
        return;
    }

    auto * runtime_ptr = static_cast<struct souz_runtime *>(runtime);
    auto * model_ptr = static_cast<struct souz_model *>(model);
    std::lock_guard<std::mutex> lock(runtime_ptr->mutex);
    clear_cached_context(runtime_ptr);
    if (model_ptr->model != nullptr) {
        llama_model_free(model_ptr->model);
        model_ptr->model = nullptr;
    }
    delete model_ptr;
}

const char * souz_llama_generate(void * runtime, void * model, const char * request_json, char * error_buffer, size_t error_buffer_size) {
    try {
        auto * runtime_ptr = require_runtime(runtime);
        auto * model_ptr = require_model(model);
        if (request_json == nullptr) {
            throw std::runtime_error("Generation request is null.");
        }

        std::lock_guard<std::mutex> lock(runtime_ptr->mutex);
        runtime_ptr->cancel_requested.store(false);
        const generation_result result = generate_impl(runtime_ptr, model_ptr, json::parse(request_json), nullptr, nullptr);
        return duplicate_string(result_json(result).dump());
    } catch (const std::exception & error) {
        clear_cached_context(static_cast<runtime_ptr>(runtime));
        write_error(error_buffer, error_buffer_size, error.what());
        return nullptr;
    }
}

const char * souz_llama_generate_stream(
    void * runtime,
    void * model,
    const char * request_json,
    souz_llama_stream_callback callback,
    void * user_data,
    char * error_buffer,
    size_t error_buffer_size
) {
    try {
        auto * runtime_ptr = require_runtime(runtime);
        auto * model_ptr = require_model(model);
        if (request_json == nullptr) {
            throw std::runtime_error("Streaming generation request is null.");
        }

        std::lock_guard<std::mutex> lock(runtime_ptr->mutex);
        runtime_ptr->cancel_requested.store(false);
        const generation_result result = generate_impl(runtime_ptr, model_ptr, json::parse(request_json), callback, user_data);
        return duplicate_string(result_json(result).dump());
    } catch (const std::exception & error) {
        clear_cached_context(static_cast<runtime_ptr>(runtime));
        write_error(error_buffer, error_buffer_size, error.what());
        return nullptr;
    }
}

void souz_llama_cancel(void * runtime) {
    if (runtime == nullptr) {
        return;
    }
    static_cast<runtime_ptr>(runtime)->cancel_requested.store(true);
}

void souz_llama_string_free(const char * ptr) {
    std::free(const_cast<char *>(ptr));
}

} // extern "C"
