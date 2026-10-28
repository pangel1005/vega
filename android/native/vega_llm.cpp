// Puente entre la app (Kotlin) y llama.cpp: el modelo de lenguaje pequeño del teléfono.
//
// Solo lo justo para charlar: cargar el modelo, leer UNA vez las instrucciones (`prime`) y
// guardar su estado, contestar a cada pregunta partiendo de ese estado y avisar de cada trozo de
// texto según sale, para que la app hable mientras el modelo sigue escribiendo.
//
// Los modelos con memoria «recurrente» (Qwen 3.5, LFM) no se pueden recortar a medias, pero sí
// copiar entera: tras leer las instrucciones se guarda el estado (`llama_state_seq_get_data`,
// ~1–21 MB) y en cada pregunta se restaura (~5 ms) y solo se lee la pregunta. Medido en el Redmi
// 15C: primer token 4,2 s → 0,7 s con 119 tokens de instrucciones.

#include <jni.h>
#include <llama.h>

#include <string>
#include <vector>

namespace {

struct Brain {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_ctx = 0;
    std::vector<uint8_t> prefix;  // estado tras leer las instrucciones
    int prefix_n = 0;             // cuántos tokens son
};

// Cuántos bytes iniciales de `s` forman caracteres UTF-8 completos (un trozo puede cortar uno).
size_t complete_utf8(const std::string & s) {
    size_t i = 0;
    while (i < s.size()) {
        const unsigned char c = s[i];
        const size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        if (i + len > s.size()) break;
        i += len;
    }
    return i;
}

// UTF-8 → jstring por UTF-16 (NewStringUTF espera «UTF-8 modificado» y rompe los emojis).
jstring to_jstring(JNIEnv * env, const std::string & s) {
    std::u16string out;
    for (size_t i = 0; i < s.size();) {
        unsigned char c = s[i];
        uint32_t cp;
        size_t len;
        if (c < 0x80) { cp = c; len = 1; }
        else if ((c >> 5) == 0x6) { cp = c & 0x1F; len = 2; }
        else if ((c >> 4) == 0xE) { cp = c & 0x0F; len = 3; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; len = 4; }
        else { cp = 0xFFFD; len = 1; }
        for (size_t k = 1; k < len && i + k < s.size(); k++) cp = (cp << 6) | (s[i + k] & 0x3F);
        i += len;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<char16_t>(cp));
        }
    }
    return env->NewString(reinterpret_cast<const jchar *>(out.data()), static_cast<jsize>(out.size()));
}

std::string to_string(JNIEnv * env, jstring s) {
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

static jlong load_impl(JNIEnv * env, jstring path, jint threads, jint threads_batch, jint n_ctx) {
    llama_backend_init();
    auto mparams = llama_model_default_params();
    llama_model * model = llama_model_load_from_file(to_string(env, path).c_str(), mparams);
    if (!model) return 0;
    auto cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = static_cast<uint32_t>(n_ctx);
    cparams.n_threads = threads;              // escribir
    cparams.n_threads_batch = threads_batch;  // leer el prompt (con más hilos lee un 25–35 % más rápido)
    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }
    auto * b = new Brain{model, ctx, llama_model_get_vocab(model), n_ctx};
    return reinterpret_cast<jlong>(b);
}

// Lee las instrucciones una vez y guarda su estado; `reply` parte siempre de él.
static jboolean prime_impl(JNIEnv * env, jlong handle, jstring jprefix) {
    auto * b = reinterpret_cast<Brain *>(handle);
    const std::string prefix = to_string(env, jprefix);
    b->prefix.clear();
    b->prefix_n = 0;
    llama_memory_clear(llama_get_memory(b->ctx), true);
    std::vector<llama_token> tokens(prefix.size() + 16);
    // Con marca de inicio si el modelo la usa (LFM la necesita; Qwen no la tiene).
    int n = llama_tokenize(b->vocab, prefix.c_str(), static_cast<int32_t>(prefix.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), true, true);
    if (n <= 0 || n >= b->n_ctx / 2) return JNI_FALSE;
    tokens.resize(n);
    if (llama_decode(b->ctx, llama_batch_get_one(tokens.data(), n)) != 0) return JNI_FALSE;
    std::vector<uint8_t> state(llama_state_seq_get_size(b->ctx, 0));
    if (llama_state_seq_get_data(b->ctx, state.data(), state.size(), 0) == 0) return JNI_FALSE;
    b->prefix = std::move(state);
    b->prefix_n = n;
    return JNI_TRUE;
}

static jstring reply_impl(JNIEnv * env, jlong handle, jstring jprompt, jint max_tokens, jobject on_piece) {
    auto * b = reinterpret_cast<Brain *>(handle);
    const std::string prompt = to_string(env, jprompt);

    llama_memory_clear(llama_get_memory(b->ctx), true);
    int base = 0;
    if (!b->prefix.empty()) {
        // Se vuelve al estado de «instrucciones ya leídas»; solo falta leer la pregunta.
        if (llama_state_seq_set_data(b->ctx, b->prefix.data(), b->prefix.size(), 0) == 0) return to_jstring(env, "");
        base = b->prefix_n;
    }
    std::vector<llama_token> tokens(prompt.size() + 16);
    int n = llama_tokenize(b->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), b->prefix.empty(), true);
    if (n < 0 || base + n + max_tokens > b->n_ctx) return to_jstring(env, "");
    tokens.resize(n);
    if (llama_decode(b->ctx, llama_batch_get_one(tokens.data(), n)) != 0) return to_jstring(env, "");

    auto * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(b->vocab), 64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.4f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass cls = env->GetObjectClass(on_piece);
    jmethodID emit = env->GetMethodID(cls, "onPiece", "(Ljava/lang/String;)Z");

    std::string all, pending;
    char buf[256];
    for (int i = 0; i < max_tokens; i++) {
        llama_token t = llama_sampler_sample(sampler, b->ctx, -1);
        if (llama_vocab_is_eog(b->vocab, t)) break;
        int len = llama_token_to_piece(b->vocab, t, buf, sizeof(buf), 0, false);
        if (len > 0) {
            pending.append(buf, len);
            size_t ok = complete_utf8(pending);
            if (ok > 0) {
                std::string piece = pending.substr(0, ok);
                pending.erase(0, ok);
                all += piece;
                jstring js = to_jstring(env, piece);
                jboolean more = env->CallBooleanMethod(on_piece, emit, js);
                env->DeleteLocalRef(js);
                // Si el código de Kotlin lanzó una excepción, no se puede seguir llamando a JNI con ella pendiente.
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    break;
                }
                if (!more) break;  // la app ya tiene bastante (o la interrumpieron)
            }
        }
        if (llama_decode(b->ctx, llama_batch_get_one(&t, 1)) != 0) break;
    }
    llama_sampler_free(sampler);
    return to_jstring(env, all);
}

static void free_impl(jlong handle) {
    auto * b = reinterpret_cast<Brain *>(handle);
    if (!b) return;
    llama_free(b->ctx);
    llama_model_free(b->model);
    delete b;
}

// Puerta de entrada desde Kotlin. Una excepción de C++ (p. ej. `std::bad_alloc` con poca memoria) que
// cruzara el límite de JNI tumbaría la app entera (`std::terminate`): aquí se convierte en un fallo normal
// (modelo sin cargar, respuesta vacía) que Kotlin ya sabe tratar.
extern "C" JNIEXPORT jlong JNICALL
Java_com_asistente_personal_local_LlamaNative_load(
    JNIEnv * env, jobject, jstring path, jint threads, jint threads_batch, jint n_ctx) {
    try {
        return load_impl(env, path, threads, threads_batch, n_ctx);
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_asistente_personal_local_LlamaNative_prime(JNIEnv * env, jobject, jlong handle, jstring jprefix) {
    try {
        return prime_impl(env, handle, jprefix);
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_asistente_personal_local_LlamaNative_reply(
    JNIEnv * env, jobject, jlong handle, jstring jprompt, jint max_tokens, jobject on_piece) {
    try {
        return reply_impl(env, handle, jprompt, max_tokens, on_piece);
    } catch (...) {
        return to_jstring(env, "");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_asistente_personal_local_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    try {
        free_impl(handle);
    } catch (...) {
    }
}
