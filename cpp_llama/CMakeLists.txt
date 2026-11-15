cmake_minimum_required(VERSION 3.22.1)
project("fable_llama" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(CMAKE_C_FLAGS_RELEASE "-O2 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O2 -DNDEBUG" CACHE STRING "" FORCE)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_CPU_HBM OFF CACHE BOOL "" FORCE)

if(DEFINED ANDROID_ABI)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
    endif()
endif()

if(NOT DEFINED LLAMA_CPP_DIR)
    message(FATAL_ERROR "LLAMA_CPP_DIR must point to llama.cpp root")
endif()

add_subdirectory(${LLAMA_CPP_DIR} build-llama EXCLUDE_FROM_ALL)

add_library(fable_llama SHARED
    fable_llama_jni.cpp
)

target_include_directories(fable_llama PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
)

target_compile_options(fable_llama PRIVATE
    -O2
    -fvisibility=hidden
)

target_link_options(fable_llama PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,--gc-sections
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(fable_llama PRIVATE
    llama
    ggml
    ${log-lib}
    ${android-lib}
)
