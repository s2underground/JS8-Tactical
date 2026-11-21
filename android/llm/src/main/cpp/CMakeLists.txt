cmake_minimum_required(VERSION 3.22)
project(js8llm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp, vendored as a submodule at <repo>/vendor/llama.cpp. Static, CPU only.
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../vendor/llama.cpp)

set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS    OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI     OFF CACHE BOOL "" FORCE)
# Every Snapdragon/Exynos/Dimensity of the last ~6 years: dot-product and fp16 instructions.
set(GGML_CPU_ARM_ARCH     "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

add_subdirectory(${LLAMA_SRC} llama-build)

add_library(js8llm SHARED js8llm_jni.cpp)
target_link_libraries(js8llm llama android log)
