cmake_minimum_required(VERSION 3.31.6)

project("chota_llama" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED LLAMA_CPP_DIR)
    message(FATAL_ERROR "LLAMA_CPP_DIR is not set; Gradle's fetchLlamaCpp task provides it")
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    set(GGML_CPU_KLEIDIAI ON)   # Arm-optimized matmul kernels (fast Q4_0)
    set(GGML_OPENMP ON)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_SYSTEM_ARCH "x86")  # emulator only
    set(GGML_CPU_KLEIDIAI OFF)
    set(GGML_OPENMP OFF)
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

add_subdirectory(${LLAMA_CPP_DIR} build-llama)

add_library(chota_llama SHARED chota_llama.cpp)

target_link_libraries(chota_llama
        llama
        llama-common
        android
        log)
