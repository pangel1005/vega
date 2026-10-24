# Librería del modelo de lenguaje del teléfono: llama.cpp + el puente con la app.
# La compila tools/android_llama.ps1 (fuera de Gradle: las rutas de Gradle en Windows
# pasaban de 250 caracteres con llama.cpp) y deja libvegallm.so en app/src/main/jniLibs.
cmake_minimum_required(VERSION 3.22)
project(vegallm CXX C)

set(LLAMA_DIR "" CACHE PATH "Carpeta con el código de llama.cpp")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)  # sin libomp.so aparte: hilos propios de ggml
add_subdirectory(${LLAMA_DIR} llama EXCLUDE_FROM_ALL)

add_library(vegallm SHARED vega_llm.cpp)
target_link_libraries(vegallm PRIVATE llama)
target_compile_features(vegallm PRIVATE cxx_std_17)
# Una sola .so, sin símbolos sobrantes.
target_link_options(vegallm PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -s)
